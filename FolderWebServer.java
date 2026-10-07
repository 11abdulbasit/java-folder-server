import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.stream.Stream;

/**
 * FolderWebServer
 * ---------------
 * A tiny web server that:
 *   1. Asks the user for a folder name (or path).
 *   2. Starts an HTTP server on localhost.
 *   3. Shows the folder's contents in the browser and serves any file
 *      (HTML, images, PDFs, text, etc.) when clicked.
 *
 * Compile : javac FolderWebServer.java
 * Run     : java FolderWebServer            (it will ask for the folder)
 *           java FolderWebServer myFolder   (or pass the folder as argument)
 * Open    : http://localhost:8080
 */
public class FolderWebServer {

    // Port the server listens on. Change it if 8080 is already in use.
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {

        // ---------- STEP 1: Get the folder name from the user ----------
        String folderName;
        if (args.length > 0) {
            // Folder given as a command-line argument
            folderName = args[0];
        } else {
            // Otherwise, ask on the console
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter the folder name to host: ");
            folderName = scanner.nextLine().trim();
        }

        // Convert the text into a Path object.
        // toAbsolutePath().normalize() gives a clean full path (removes "../" tricks).
        Path rootFolder = Paths.get(folderName).toAbsolutePath().normalize();

        // Make sure the folder really exists and is a directory
        if (!Files.exists(rootFolder) || !Files.isDirectory(rootFolder)) {
            System.out.println("Error: '" + rootFolder + "' is not a valid folder.");
            return; // stop the program
        }

        // ---------- STEP 2: Create and start the HTTP server ----------
        // InetSocketAddress(PORT) -> listen on all network interfaces at this port.
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Every request ("/" and anything under it) is handled by FileHandler.
        server.createContext("/", new FileHandler(rootFolder));

        // null = use the default executor (handles requests one at a time).
        server.setExecutor(null);
        server.start();

        System.out.println("Hosting folder : " + rootFolder);
        System.out.println("Open in browser: http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    /**
     * FileHandler runs every time the browser makes a request.
     * It decides: is the request for a folder (show a listing)
     * or for a file (send its bytes)?
     */
    static class FileHandler implements HttpHandler {

        private final Path root; // the folder the user asked us to host

        FileHandler(Path root) {
            this.root = root;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // ---------- STEP 3: Work out what the browser asked for ----------
                // Example: browser requests http://localhost:8080/images/cat.png
                // -> requestPath = "/images/cat.png"
                String requestPath = exchange.getRequestURI().getPath();

                // Browsers encode spaces as %20 etc. Decode it back to normal text.
                requestPath = URLDecoder.decode(requestPath, StandardCharsets.UTF_8);

                // Remove the leading "/" so it can be joined to the root folder.
                String relative = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;

                // Join root + requested path, then clean it up.
                Path target = root.resolve(relative).normalize();

                // ---------- SECURITY CHECK ----------
                // Stop "path traversal" attacks such as /../../secret.txt
                // The final path MUST still be inside the root folder.
                if (!target.startsWith(root)) {
                    sendText(exchange, 403, "403 - Forbidden");
                    return;
                }

                // ---------- STEP 4: Decide what to send back ----------
                if (!Files.exists(target)) {
                    sendText(exchange, 404, "404 - Not Found");

                } else if (Files.isDirectory(target)) {
                    // If the folder has an index.html, serve that like a real website
                    Path index = target.resolve("index.html");
                    if (Files.exists(index)) {
                        sendFile(exchange, index);
                    } else {
                        // Otherwise, show a clickable list of the folder's contents
                        sendDirectoryListing(exchange, target, requestPath);
                    }

                } else {
                    // It's a normal file -> read it and send it to the browser
                    sendFile(exchange, target);
                }

            } catch (Exception e) {
                // Anything unexpected -> 500 Internal Server Error
                sendText(exchange, 500, "500 - Server Error: " + e.getMessage());
            } finally {
                exchange.close(); // always close the connection
            }
        }

        /** Reads a file from disk and sends it to the browser. */
        private void sendFile(HttpExchange exchange, Path file) throws IOException {
            // Read the whole file into memory as bytes
            byte[] data = Files.readAllBytes(file);

            // Tell the browser what kind of file this is (so it can display it properly)
            exchange.getResponseHeaders().set("Content-Type", getMimeType(file));

            // 200 = OK, and data.length = how many bytes we're sending
            exchange.sendResponseHeaders(200, data.length);

            // Write the file's bytes to the response body
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        /** Builds an HTML page listing everything inside a folder. */
        private void sendDirectoryListing(HttpExchange exchange, Path dir, String requestPath)
                throws IOException {

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
                .append("<title>Index of ").append(escapeHtml(requestPath)).append("</title>")
                .append("<style>body{font-family:sans-serif;margin:40px;}")
                .append("a{text-decoration:none;color:#0366d6;}li{margin:6px 0;}</style>")
                .append("</head><body>")
                .append("<h2>Index of ").append(escapeHtml(requestPath)).append("</h2><ul>");

            // Add a "go up one level" link unless we're already at the top
            if (!requestPath.equals("/")) {
                html.append("<li><a href='../'>&#128193; ..</a></li>");
            }

            // Loop through every item in the folder (sorted by name)
            try (Stream<Path> items = Files.list(dir)) {
                items.sorted().forEach(item -> {
                    String name = item.getFileName().toString();
                    boolean isDir = Files.isDirectory(item);

                    // Encode the name for the URL (spaces -> %20). Folders end with "/"
                    String link = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                    if (isDir) link += "/";

                    html.append("<li><a href='").append(link).append("'>")
                        .append(isDir ? "&#128193; " : "&#128196; ")   // folder / file icon
                        .append(escapeHtml(name)).append(isDir ? "/" : "")
                        .append("</a></li>");
                });
            }

            html.append("</ul></body></html>");

            byte[] data = html.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        /** Sends a short plain-text message (used for errors like 404). */
        private void sendText(HttpExchange exchange, int status, String message) throws IOException {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(status, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        /** Picks the MIME type (file type label) from the file extension. */
        private String getMimeType(Path file) {
            String name = file.getFileName().toString().toLowerCase();

            if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
            if (name.endsWith(".css"))  return "text/css; charset=UTF-8";
            if (name.endsWith(".js"))   return "application/javascript; charset=UTF-8";
            if (name.endsWith(".json")) return "application/json; charset=UTF-8";
            if (name.endsWith(".txt") || name.endsWith(".java") || name.endsWith(".md"))
                return "text/plain; charset=UTF-8";
            if (name.endsWith(".png"))  return "image/png";
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
            if (name.endsWith(".gif"))  return "image/gif";
            if (name.endsWith(".svg"))  return "image/svg+xml";
            if (name.endsWith(".pdf"))  return "application/pdf";
            if (name.endsWith(".mp4"))  return "video/mp4";
            if (name.endsWith(".mp3"))  return "audio/mpeg";

            // Unknown type -> let the browser download it
            return "application/octet-stream";
        }

        /** Prevents file names containing < > & from breaking the HTML page. */
        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;");
        }
    }
}
