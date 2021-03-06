package de.gesellix.docker.testutil

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import okio.Okio

import java.util.concurrent.Executors

class HttpTestServer {

    HttpServer httpServer

    def start() {
        return start('/test/', new ReverseHandler())
    }

    def start(String context, HttpHandler handler) {
        InetSocketAddress addr = new InetSocketAddress(0)
        httpServer = HttpServer.create(addr, 0)
        httpServer.with {
            createContext(context, handler)
            setExecutor(Executors.newCachedThreadPool())
            start()
        }
        return httpServer.address
    }

    def stop() {
        if (httpServer) {
            httpServer.stop(0)
        }
    }

    static class ReverseHandler implements HttpHandler {

        @Override
        void handle(HttpExchange httpExchange) {
            if (httpExchange.requestMethod == 'GET') {
                httpExchange.responseHeaders.set('Content-Type', 'text/plain')
                final String query = httpExchange.requestURI.rawQuery

                if (!query || !query.contains('string')) {
                    httpExchange.sendResponseHeaders(400, 0)
                    return
                }

                final String[] param = query.split('=')
                assert param.length == 2 && param[0] == 'string'

                httpExchange.sendResponseHeaders(200, 0)
                httpExchange.responseBody.write(param[1].reverse().bytes)
                httpExchange.responseBody.close()
            }
        }
    }

    static class FileServer implements HttpHandler {

        URL file

        FileServer(URL file) {
            this.file = file
        }

        @Override
        void handle(HttpExchange httpExchange) {
            if (httpExchange.requestMethod == 'GET') {
                httpExchange.sendResponseHeaders(200, 0)
                httpExchange.responseBody.write(toString((file as URL).newInputStream()).bytes)
                httpExchange.responseBody.close()
            }
        }

        private String toString(InputStream source) {
            Okio.buffer(Okio.source(source)).readUtf8()
        }
    }
}
