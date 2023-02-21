package cn.zbx1425.resourcepackupdater.io;

import cn.zbx1425.resourcepackupdater.drm.AssetEncryption;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class LocalMetadata {

    public List<String> dirs = new ArrayList<>();
    public HashMap<String, byte[]> files = new HashMap<>();

    public String baseDir;
    public HashCache hashCache;

    public final String HASH_CACHE_FILE_NAME = "updater_hash_cache.bin";

    public LocalMetadata(String baseDir) {
        this.baseDir = baseDir;
        this.hashCache = new HashCache();
    }

    public void scanDir() throws Exception {
        dirs.clear();
        files.clear();
        hashCache.load(Path.of(baseDir, HASH_CACHE_FILE_NAME));

        Path basePath = Paths.get(baseDir);
        if (!Files.isDirectory(basePath)) {
            Files.createDirectories(basePath);
        }
        try (var walkStream = Files.walk(basePath)) {
            for (var entry : walkStream.toList()) {
                var relPath = basePath.relativize(entry).toString().replace('\\', '/');
                if (relPath.equals(HASH_CACHE_FILE_NAME)) continue;
                if (Files.isDirectory(entry)) {
                    dirs.add(relPath);
                } else {
                    AssetEncryption.encryptIfRaw(entry.toFile());
                    files.put(relPath, hashCache.getDigest(relPath, entry.toFile()));
                }
            }
        }

        hashCache.save(Path.of(baseDir, HASH_CACHE_FILE_NAME));
    }

    public byte[] getDirChecksum() throws Exception {
        ByteBuf buf = Unpooled.buffer(1024 * 512);
        dirs.stream().sorted().forEach(dir -> buf.writeCharSequence(dir, StandardCharsets.UTF_8));
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            buf.writeCharSequence(entry.getKey(), StandardCharsets.UTF_8);
            buf.writeBytes(entry.getValue());
        });
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        buf.capacity(buf.writerIndex());
        InputStream fis = new ByteArrayInputStream(buf.array());
        int n = 0;
        byte[] buffer = new byte[8192];
        while (n != -1) {
            n = fis.read(buffer);
            if (n > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return digest.digest();
    }

    public List<String> getDirsToCreate(RemoteMetadata other) {
        return other.dirs.stream().filter(dir -> !dirs.contains(dir)).toList();
    }

    public List<String> getDirsToDelete(RemoteMetadata other) {
        return dirs.stream().filter(dir -> !other.dirs.contains(dir)).toList();
    }

    public List<String> getFilesToCreate(RemoteMetadata other) {
        return other.files.keySet().stream().filter(file -> !files.containsKey(file)).toList();
    }

    public List<String> getFilesToUpdate(RemoteMetadata other) {
        return files.entrySet().stream().filter(
                entry -> other.files.containsKey(entry.getKey())
                        && !Arrays.equals(other.files.get(entry.getKey()), entry.getValue())
        ).map(Map.Entry::getKey).toList();
    }

    public List<String> getFilesToDelete(RemoteMetadata other) {
        return files.keySet().stream().filter(file -> !other.files.containsKey(file)).toList();
    }

    public static void unzipFileTo(String src, File destDir, ProgressReceiver cb) throws Exception {
        ZipFile zipFile = new ZipFile(src);
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        int processed = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();
            File newFile = new File(destDir, zipEntry.getName());
            String destDirPath = destDir.getCanonicalPath();
            String destFilePath = newFile.getCanonicalPath();

            if (!destFilePath.startsWith(destDirPath + File.separator)) {
                throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
            }

            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                InputStream zis = zipFile.getInputStream(zipEntry);
                FileOutputStream fos = new FileOutputStream(newFile);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                IOUtils.copy(zis, bos);
                bos.close();
                fos.close();
                zis.close();
            }
            cb.setSecondaryProgress(
                    processed * 1f / zipFile.size(),
                    String.format(": %5d / %5d entries decompressed", processed, zipFile.size())
            );
            processed++;
        }
        zipFile.close();
    }
}
