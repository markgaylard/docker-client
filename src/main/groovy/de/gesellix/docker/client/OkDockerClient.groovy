package de.gesellix.docker.client

import de.gesellix.docker.client.rawstream.RawInputStream
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.io.RealConnection
import okhttp3.ws.WebSocketCall
import okio.Okio
import okio.Sink
import okio.Source
import org.apache.commons.io.IOUtils
import org.apache.commons.io.output.NullOutputStream

import java.util.regex.Pattern

import static java.util.concurrent.TimeUnit.MILLISECONDS

@Slf4j
class OkDockerClient implements HttpClient {

    DockerURLHandler dockerURLHandler

    Proxy proxy
    DockerConfig config
    DockerSslSocketFactory dockerSslSocketFactory

    OkDockerClient() {
        dockerSslSocketFactory = new DockerSslSocketFactory()
        config = new DockerConfig()
        proxy = Proxy.NO_PROXY
    }

    @Override
    def head(String path) {
        return head(ensureValidRequestConfig(path))
    }

    @Override
    def head(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "HEAD"
        return request(config)
    }

    @Override
    def get(String path) {
        return get(ensureValidRequestConfig(path))
    }

    @Override
    def get(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "GET"
        return request(config)
    }

    @Override
    def put(String path) {
        return put(ensureValidRequestConfig(path))
    }

    @Override
    def put(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "PUT"
        return request(config)
    }

    @Override
    def post(String path) {
        return post(ensureValidRequestConfig(path))
    }

    @Override
    def post(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "POST"
        return request(config)
    }

    @Override
    def delete(String path) {
        return delete(ensureValidRequestConfig(path))
    }

    @Override
    def delete(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "DELETE"
        return request(config)
    }

    @Override
    WebSocketCall webSocketCall(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "GET"

        Request.Builder requestBuilder = prepareRequest(new Request.Builder(), config)
        def request = requestBuilder.build()

        OkHttpClient.Builder clientBuilder = prepareClient(new OkHttpClient.Builder(), config.timeout ?: 0)
        def client = newClient(clientBuilder)

        def wsCall = WebSocketCall.create(client, request)
        wsCall
    }

    static class ConnectionProvider implements Interceptor {

        Sink sink = null
        Source source = null

        @Override
        Response intercept(Interceptor.Chain chain) throws IOException {
            // attention: this connection is *per request*, so sink and source might be overwritten
            RealConnection connection = chain.connection() as RealConnection
            source = connection.source
            sink = connection.sink
            return chain.proceed(chain.request())
        }
    }

    static class ResponseCallback implements Callback {
        InputStream stdin
        ConnectionProvider connectionProvider
        OkHttpClient client
        Closure onResponseCallback
        Closure done

        ResponseCallback(def stdin, def connectionProvider, def client, def onResponseCallback, def done) {
            this.stdin = stdin
            this.connectionProvider = connectionProvider
            this.client = client
            this.onResponseCallback = onResponseCallback
            this.done = done
        }

        @Override
        void onFailure(Call call, IOException e) {
            log.error "connection failed: ${e.message}"
        }

        @Override
        void onResponse(Call call, Response response) throws IOException {
            ensureTcpUpgrade(response)

            if (stdin != null) {
                // pass input from the client via stdin and pass it to the output stream
                // running it in an own thread allows the client to gain back control
                def reader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            def bufferedSink = Okio.buffer(connectionProvider.sink)
                            def stdinSource = Okio.source(stdin)
                            while (stdinSource.read(bufferedSink.buffer(), 1024) != -1) {
                                bufferedSink.flush()
                            }
                            bufferedSink.close()

                            def bufferedStdout = Okio.buffer(Okio.sink(System.out))
                            while (connectionProvider.source.read(bufferedStdout.buffer(), 1024) != -1) {
                                bufferedStdout.flush()
                            }
                        } catch (Exception e) {
                            log.error("error", e)
                        } finally {
                            client.dispatcher().executorService().shutdown()
                        }

                        done(response)
                    }
                })
                reader.start()
            }
            onResponseCallback(response)
        }

        def ensureTcpUpgrade(Response response) {
            if (response.code() != 101) {
                log.error "expected status 101, but got ${response.code()} ${response.message()}"
                throw new ProtocolException("Expected HTTP 101 Connection Upgrade")
            }
            String headerConnection = response.header("Connection")
            if (headerConnection.toLowerCase() != "upgrade") {
                log.error "expected 'Connection: Upgrade', but got 'Connection: ${headerConnection}'"
                throw new ProtocolException("Expected 'Connection: Upgrade'")
            }
            String headerUpgrade = response.header("Upgrade")
            if (headerUpgrade.toLowerCase() != "tcp") {
                log.error "expected 'Upgrade: tcp', but got 'Upgrade: ${headerUpgrade}'"
                throw new ProtocolException("Expected 'Upgrade: tcp'")
            }
        }
    }

    @Override
    def attach(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)
        config.method = "POST"
        config.headers = [
                "Upgrade"   : "tcp",
                "Connection": "Upgrade",
        ]
        config.body = new ByteArrayInputStream()
        config.requestContentType = "text/plain"

        OutputStream stdout = config.callback.stdout
        OutputStream stderr = config.callback.stderr
        InputStream stdin = config.callback.stdin
        boolean multiplexStreams = config.multiplexStreams
        Closure onResponseCallback = config.callback.onResponseCallback
        Closure done = config.callback.done

        Request.Builder requestBuilder = prepareRequest(new Request.Builder(), config)
        def request = requestBuilder.build()

        OkHttpClient.Builder clientBuilder = prepareClient(new OkHttpClient.Builder(), config.timeout ?: 0)

        def connectionProvider = new ConnectionProvider()

        clientBuilder.addNetworkInterceptor(connectionProvider)
        def client = newClient(clientBuilder)

        def responseCallback = new ResponseCallback(stdin, connectionProvider, client, onResponseCallback, done)

        log.debug("${request.method()} ${request.url()} using proxy: ${client.proxy()}")

        // if   responseCallback != null -> enqueue(responseCallback)
        // else                          -> execute()
        client.newCall(request).enqueue(responseCallback)
    }

    def request(Map requestConfig) {
        def config = ensureValidRequestConfig(requestConfig)

        Request.Builder requestBuilder = prepareRequest(new Request.Builder(), config)
        def request = requestBuilder.build()

        OkHttpClient.Builder clientBuilder = prepareClient(new OkHttpClient.Builder(), config.timeout ?: 0)
        def client = newClient(clientBuilder)

        log.debug("${request.method()} ${request.url()} using proxy: ${client.proxy()}")

        def response = client.newCall(request).execute()
        log.debug("response: ${response.toString()}")
        def dockerResponse = handleResponse(response, config)
        if (!dockerResponse.stream) {
//            log.warn("closing response...")
            response.close()
        }

        return dockerResponse
    }

    def prepareRequest(Request.Builder builder, Map config) {
        def method = config.method as String
        def contentType = config.requestContentType as String
        def additionalHeaders = config.headers
        def body = config.body

        def (String protocol, String host, int port) = getProtocolAndHost()
        def path = config.path as String
        if (config.apiVersion) {
            path = "/${config.apiVersion}${path}".toString()
        }
        String queryAsString = (config.query) ? "${queryToString(config.query as Map)}" : ""

        def urlBuilder = new HttpUrl.Builder()
                .addPathSegments(path)
                .encodedQuery(queryAsString ?: null)
        def httpUrl = createUrl(urlBuilder, protocol, host, port)


        def requestBody = createRequestBody(method, contentType, body)
        builder
                .method(method, requestBody)
                .url(httpUrl)
                .cacheControl(CacheControl.FORCE_NETWORK)

        additionalHeaders?.each { String key, String value ->
            builder.header(key, value)
        }
        builder
    }

    private OkHttpClient.Builder prepareClient(OkHttpClient.Builder builder, int currentTimeout) {
        def (String protocol, String host, int port) = getProtocolAndHost()
        if (protocol == "unix") {
            def unixSocketFactory = new UnixSocketFactory()
            builder
                    .socketFactory(unixSocketFactory)
                    .dns(unixSocketFactory)
                    .build()
        } else if (protocol == 'npipe') {
            def npipeSocketFactory = new NpipeSocketFactory()
            builder
                    .socketFactory(npipeSocketFactory)
                    .dns(npipeSocketFactory)
                    .build()
        } else if (protocol == 'https') {
            def certPath = this.config.certPath as String
            def dockerSslSocket = dockerSslSocketFactory.createDockerSslSocket(certPath)
            // warn, if null?
            if (dockerSslSocket) {
                builder
                        .sslSocketFactory(dockerSslSocket.sslSocketFactory, dockerSslSocket.trustManager)
                        .build()
            }
        }
        builder
                .proxy(proxy)

        // do we need to disable the timeout for streaming?
        builder
                .connectTimeout(currentTimeout as int, MILLISECONDS)
                .readTimeout(currentTimeout as int, MILLISECONDS)
        builder
    }

    def OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
        clientBuilder.build()
    }

    private HttpUrl createUrl(HttpUrl.Builder urlBuilder, String protocol, String host, int port) {
        def httpUrl
        if (protocol == "unix") {
            httpUrl = urlBuilder
                    .scheme("http")
                    .host(UnixSocketFactory.UnixSocket.encodeHostname(host))
//                    .port(/not/allowed/for/unix/socket/)
                    .build()
        } else if (protocol == "npipe") {
            httpUrl = urlBuilder
                    .scheme("http")
                    .host(NpipeSocketFactory.NamedPipeSocket.encodeHostname(host))
//                    .port(/not/allowed/for/npipe/socket/)
                    .build()
        } else {
            httpUrl = urlBuilder
                    .scheme(protocol)
                    .host(host)
                    .port(port)
                    .build()
        }
        httpUrl
    }

    def createRequestBody(String method, String contentType, def body) {
        if (!body && HttpMethod.requiresRequestBody(method)) {
            return RequestBody.create(MediaType.parse("application/json"), "{}")
        }

        def requestBody = null
        if (body) {
            switch (contentType) {
                case "application/json":
                    def json = new JsonBuilder()
                    json body
                    requestBody = RequestBody.create(MediaType.parse("application/json"), json.toString())
                    break
                case "application/octet-stream":
                    def source = Okio.source(body as InputStream)
                    def buffer = Okio.buffer(source)
                    requestBody = RequestBody.create(MediaType.parse("application/octet-stream"), buffer.readByteArray())
                    break
                default:
                    def source = Okio.source(body as InputStream)
                    def buffer = Okio.buffer(source)
                    requestBody = RequestBody.create(MediaType.parse(contentType), buffer.readByteArray())
                    break
            }
        }
        requestBody
    }

    DockerResponse handleResponse(Response httpResponse, Map config) {
        def response = readHeaders(httpResponse)

        if (response.status.code == 204) {
            if (response.stream) {
                // redirect the response body to /dev/null, since it's expected to be empty
                IOUtils.copy(response.stream as InputStream, new NullOutputStream())
            }
            return response
        }

        switch (response.mimeType) {
            case "application/vnd.docker.raw-stream":
                InputStream rawStream = new RawInputStream(httpResponse.body().byteStream())
                response.stream = rawStream
                if (config.stdout) {
                    log.debug("redirecting to stdout.")
                    IOUtils.copy(response.stream as InputStream, config.stdout as OutputStream)
                    response.stream = null
                }
                break
            case "application/json":
                def content = new JsonContentHandler(config.async as boolean).getContent(
                        httpResponse.body().byteStream(),
                        httpResponse.header("transfer-encoding") == "chunked")
                consumeResponseBody(response, content, config)
                break
            case "text/html":
            case "text/plain":
                def stream = httpResponse.body().byteStream()
                consumeResponseBody(response, stream, config)
                break
            case "application/octet-stream":
                def stream = httpResponse.body().byteStream()
                log.debug("passing through via `response.stream`.")
                if (config.stdout) {
                    IOUtils.copy(stream, config.stdout as OutputStream)
                    response.stream = null
                } else {
                    response.stream = stream
                }
                break
            case "application/x-tar":
                if (response.stream) {
                    if (config.stdout) {
                        log.debug("redirecting to stdout.")
                        IOUtils.copy(response.stream as InputStream, config.stdout as OutputStream)
                        response.stream = null
                    } else {
                        log.warn("stream won't be consumed.")
                    }
                }
                break
            default:
                log.warn("unexpected mime type '${response.mimeType}'.")
                def body = httpResponse.body()
                if (body.contentLength() == -1) {
                    def stream = body.byteStream()
                    log.debug("passing through via `response.stream`.")
                    if (config.stdout) {
                        IOUtils.copy(stream, config.stdout as OutputStream)
                        response.stream = null
                    } else {
                        response.stream = stream
                    }
                } else {
                    log.debug("passing through via `response.content`.")
                    response.content = body.string()
                    response.stream = null
                }
                break
        }

        return response
    }

    def readHeaders(Response httpResponse) {
        def dockerResponse = new DockerResponse()

        dockerResponse.status = [text   : httpResponse.message(),
                                 code   : httpResponse.code(),
                                 success: httpResponse.successful]
        log.debug("status: ${dockerResponse.status}")

        def headers = httpResponse.headers()
        log.debug("headers: ${headers}")
        dockerResponse.headers = headers

        String contentType = headers['content-type']
        log.trace("content-type: ${contentType}")
        dockerResponse.contentType = contentType

        String contentLength = headers['content-length'] ?: "-1"
        log.trace("content-length: ${contentLength}")
        dockerResponse.contentLength = contentLength

        String mimeType = getMimeType(contentType)
        log.trace("mime type: ${mimeType}")
        dockerResponse.mimeType = mimeType

        if (dockerResponse.status.success) {
            dockerResponse.stream = httpResponse.body().byteStream()
        } else {
            dockerResponse.stream = null
        }
        return dockerResponse
    }

    def consumeResponseBody(DockerResponse response, Object content, Map config) {
        if (content instanceof InputStream) {
            if (config.async) {
                response.stream = content as InputStream
            } else if (config.stdout) {
                IOUtils.copy(content as InputStream, config.stdout as OutputStream)
                response.stream = null
            } else if (response.contentLength && Integer.parseInt(response.contentLength as String) >= 0) {
                response.content = IOUtils.toString(content as InputStream)
                response.stream = null
            } else {
                response.stream = content as InputStream
            }
        } else {
            response.content = content
            response.stream = null
        }
    }

    Map ensureValidRequestConfig(String path) {
        return ensureValidRequestConfig([path: path])
    }

    Map ensureValidRequestConfig(Map config) {
        if (!config?.path) {
            log.error("bad request config: ${config}")
            throw new IllegalArgumentException("bad request config")
        }
        if (config.path.startsWith("/")) {
            config.path = config.path.substring("/".length())
        }
        return config
    }

    def getProtocolAndHost() {
        return getDockerURLHandler().getProtocolAndHost(this.config.dockerHost)
    }

    def queryToString(Map queryParameters) {
        def queryAsString = queryParameters.collect { key, value ->
            if (value instanceof String) {
                "${URLEncoder.encode("$key".toString(), "UTF-8")}=${URLEncoder.encode("$value".toString(), "UTF-8")}"
            } else {
                value.collect {
                    "${URLEncoder.encode("$key".toString(), "UTF-8")}=${URLEncoder.encode("$it".toString(), "UTF-8")}"
                }.join("&")
            }
        }
        return queryAsString.join("&")
    }

    def getDockerURLHandler() {
        if (!dockerURLHandler) {
            dockerURLHandler = new DockerURLHandler(config: config)
        }
        dockerURLHandler
    }

    String getMimeType(String contentTypeHeader) {
        return contentTypeHeader?.replace(" ", "")?.split(";")?.first()
    }

    String getCharset(String contentTypeHeader) {
        String charset = "utf-8"
        def matcher = Pattern.compile("[^;]+;\\s*charset=([^;]+)(;[^;]*)*").matcher(contentTypeHeader)
        if (matcher.find()) {
            charset = matcher.group(1)
        }
        return charset
    }
}
