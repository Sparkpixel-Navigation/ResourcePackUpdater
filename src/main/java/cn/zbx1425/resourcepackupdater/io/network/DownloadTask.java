package cn.zbx1425.resourcepackupdater.io.network;

import cn.zbx1425.resourcepackupdater.ResourcePackUpdater;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class DownloadTask {

    public long totalBytes;
    public long downloadedBytes;
    public final long expectedSize;

    protected final DownloadDispatcher dispatcher;
    private final URI requestUri;

    public String fileName;

    public int failedAttempts = 0;

    public DownloadTask(DownloadDispatcher dispatcher, String url, String fileName, long expectedSize) {
        this.dispatcher = dispatcher;
        this.requestUri = URI.create(url);
        this.fileName = fileName;
        this.expectedSize = expectedSize;
    }

    public void runBlocking(OutputStream target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) requestUri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "ResourcePackUpdater/" + ResourcePackUpdater.MOD_VERSION + " +https://www.zbx1425.cn");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);

        if (connection.getResponseCode() >= 400) {
            throw new IOException("Server returned HTTP " + connection.getResponseCode() + " "
                    + new String(IOUtils.toByteArray(connection.getErrorStream()), StandardCharsets.UTF_8));
        }

        totalBytes = connection.getContentLengthLong();
        if (totalBytes == -1) totalBytes = expectedSize;
        final long[] accountedAmount = {0};

        try {
            try (BufferedOutputStream bos = new BufferedOutputStream(target);
                 InputStream inputStream = unwrapHttpResponse(connection)) {
                final ProgressOutputStream pOfs = new ProgressOutputStream(bos, new ProgressOutputStream.WriteListener() {
                    final long noticeDivisor = 8192;

                    @Override
                    public void registerWrite(long amountOfBytesWritten) throws IOException {
                        if (accountedAmount[0] / noticeDivisor != amountOfBytesWritten / noticeDivisor) {
                            downloadedBytes += (amountOfBytesWritten - accountedAmount[0]);
                            dispatcher.onDownloadProgress((amountOfBytesWritten - accountedAmount[0]));
                            accountedAmount[0] = amountOfBytesWritten;
                        }
                    }
                });
                IOUtils.copy(new BufferedInputStream(inputStream), pOfs);
            }
            target.close();
        } catch (Exception ex) {
            dispatcher.onDownloadProgress(-accountedAmount[0]);
            downloadedBytes = 0;
            throw ex;
        }
        dispatcher.onDownloadProgress(totalBytes - accountedAmount[0]);
        downloadedBytes = totalBytes;
    }

    private static InputStream unwrapHttpResponse(HttpURLConnection connection) throws IOException {
        String contentEncoding = connection.getContentEncoding();
        if (contentEncoding == null || contentEncoding.isEmpty()) {
            return connection.getInputStream();
        } else if ("gzip".equalsIgnoreCase(contentEncoding)) {
            return new GZIPInputStream(connection.getInputStream());
        } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
            return new InflaterInputStream(connection.getInputStream());
        } else {
            throw new IOException("Unsupported Content-Encoding: " + contentEncoding);
        }
    }
}
