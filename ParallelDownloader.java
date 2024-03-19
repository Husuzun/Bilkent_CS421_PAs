import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ParallelDownloader {

    public static void main(String[] args) {

        String indexFileUrl = args[0];
        int numberOfThreads = Integer.parseInt(args[1]);
        String indexContent = downloadFile(indexFileUrl);

        if (indexContent == null) {
            System.err.println("Index file not found");
            System.exit(1);
        }

        List<String> fileUrls = parseIndexFile(indexContent);
        for (String fileUrl : fileUrls) {
            System.out.println(fileUrl);
            long fileSize = checkFileSize(fileUrl);
            if (fileSize != -1) {
                System.out.println("File size:" + fileSize + " bytes");
            } else {
                System.out.println("Could not determine file size.");
            }
            if (fileSize != -1) {
                System.out.println("It is working");
                downloadFileInParallel(fileUrl, fileSize, numberOfThreads);
            } else {
                System.err.println("File is not found: " + fileUrl);
            }
        }
    }

    private static String downloadFile(String urlString) {
        StringBuilder response = new StringBuilder();
        try {
            
            @SuppressWarnings("deprecation")
            URL url = new URL(urlString);
            String host = url.getHost();
            String path = url.getPath().isEmpty() ? "/" : url.getPath();
            int port = url.getPort() != -1 ? url.getPort() : 80;

            try (Socket socket = new Socket(host, port);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println("GET " + path + " HTTP/1.1");
                out.println("Host: " + host);
                out.println("Connection: close");
                out.println();

                String line;
                boolean isBody = false;
                while ((line = in.readLine()) != null) {
                    if (isBody) {
                        response.append(line).append("\n");
                    } else if (line.isEmpty()) {
                        isBody = true;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return response.toString();
    }

    private static List<String> parseIndexFile(String indexContent) {
        List<String> urls = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(indexContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                urls.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return urls;
    }

    private static long checkFileSize(String urlString) {
    long contentLength = -1;

    try {
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            urlString = "http://" + urlString;
        }

        @SuppressWarnings("deprecation")
        URL url = new URL(urlString);
        String host = url.getHost();
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        int port = url.getPort() != -1 ? url.getPort() : 80;

        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("HEAD " + path + " HTTP/1.1");
            out.println("Host: " + host);
            out.println("Connection: close");
            out.println();

            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("Content-Length: ")) {
                    contentLength = Long.parseLong(line.substring("Content-Length: ".length()).trim());
                    break;
                }
            }
        }
    } catch (Exception e) {
        e.printStackTrace();
    }

    return contentLength;
}


    private static void downloadFileInParallel(String urlString, long fileSize, int numberOfThreads) {
        long partSize = fileSize / numberOfThreads;
        List<Thread> threads = new ArrayList<>();
        List<String> partFiles = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            long startByte = i * partSize;
            long endByte = (i < numberOfThreads - 1) ? (startByte + partSize - 1) : fileSize;

            String partFileName = "part" + (i+1) + ".tmp";
            partFiles.add(partFileName);

            Thread thread = new Thread(new DownloadTask(urlString, startByte, endByte, partFileName));
            System.out.println("File: "+ partFileName + " Byte: " + (endByte-startByte));
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        try {
            mergeFiles(partFiles, new File(getFileNameFromURL(urlString)));
        } catch (IOException e) {
            e.printStackTrace();
        }
        partFiles.forEach(partFile -> new File(partFile).delete());
    }

    private static class DownloadTask implements Runnable {
        private String urlT;
        private long start;
        private long end;
        private String outputFileName;

        public DownloadTask(String urlT, long start, long end, String outputFileName) {
            this.urlT = urlT;
            this.start = start;
            this.end = end;
            this.outputFileName = outputFileName;
        }

        @Override
        public void run() {
            try {
                if (!urlT.startsWith("http://") && !urlT.startsWith("https://")) {
                    urlT = "http://" + urlT;
                }

                @SuppressWarnings("deprecation")
                URL url = new URL(urlT);
                String host = url.getHost();
                String path = url.getPath().isEmpty() ? "/" : url.getPath();
                int port = url.getPort() != -1 ? url.getPort() : 80;

                try (Socket socket = new Socket(host, port);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                     BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                     FileOutputStream fileOut = new FileOutputStream(outputFileName)) {

                    out.println("GET " + path + " HTTP/1.1");
                    out.println("Host: " + host);
                    out.println("Range: bytes=" + start + "-" + end);
                    out.println("Connection: close");
                    out.println();

                    String line;
                    boolean isBody = false;
                    while (!isBody) {
                        line = readLine(in);
                        if (line.isEmpty()) {
                            isBody = true;
                        }
                    }

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fileOut.write(buffer, 0, bytesRead);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') {
                    in.mark(1);
                    if (in.read() != '\n') {
                        in.reset();
                    }
                    break;
                } else if (c == '\n') {
                    break;
                } else {
                    line.append((char) c);
                }
            }
            return line.toString();
        }
    }

    private static void mergeFiles(List<String> partFiles, File outputFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedOutputStream mergeOut = new BufferedOutputStream(fos)) {
            for (String partFile : partFiles) {
                Files.copy(new File(partFile).toPath(), mergeOut);
            }
        }
    }

    private static String getFileNameFromURL(String urlString) {
        try {
            if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
                urlString = "http://" + urlString;
            }
            @SuppressWarnings("deprecation")
            URL url = new URL(urlString);
            String path = url.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return "temp";
        }
    }
}
