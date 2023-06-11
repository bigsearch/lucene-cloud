package io.bigsearch.lucene.store.s3;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import io.bigsearch.lucene.store.s3.index.S3IndexInput;
import org.apache.lucene.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.nio.file.Path;

/**
 * A S3 based implementation of a Lucene <code>Directory</code> allowing the storage of a Lucene index within S3.
 * The directory works against a single object prefix, where the binary data is stored in <code>objects</code>.
 * Each "object" has an entry in the S3.
 *
 * @author swkim86
 */
public class S3Directory extends MMapDirectory {
    private static final Logger logger = LoggerFactory.getLogger(S3Directory.class);

    private final String bucket;

    private final String prefix;

    private final Path cachePath;

    private final S3Client s3 = S3Client.create();

    /**
     * Creates a new S3 directory.
     *
     * @param bucket The bucket name
     */
    public S3Directory(final String bucket, final String prefix, final Path bufferPath, final Path cachePath) throws IOException {
        super(bufferPath);
        this.bucket = bucket.toLowerCase();
        this.prefix = prefix.toLowerCase();
        this.cachePath = cachePath;

        // Delete all the local orphan files not synced to S3 in the fsPath
        String[] files = super.listAll();
        for (String file : files) {
            super.deleteFile(file);
        }

        System.out.printf("list after delete at init {}\n", super.listAll());
    }

    /**
     * ********************************************************************************************
     * DIRECTORY METHODS
     * ********************************************************************************************
     */
    @Override
    public String[] listAll() {
        if (logger.isDebugEnabled()) {
            logger.info("listAll({})", bucket);
        }
        ArrayList<String> names = new ArrayList<>();

        try {
            ArrayList<String> rawNames = new ArrayList<>();
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build();
            ListObjectsV2Iterable responses = s3.listObjectsV2Paginator(request);
            for (ListObjectsV2Response response : responses) {
                rawNames.addAll(response.contents().stream().map(S3Object::key).toList());
            }

            // Remove prefix from S3 keys
            for (String rawName : rawNames) {
                if (rawName.equals(prefix)) {
                    continue;
                }
                names.add(rawName.substring(prefix.length()));
            }

            // Get file list in local directory
            String[] filePaths = super.listAll();

            // Add local file paths to list
            if (filePaths.length > 0) {
                names.addAll(Arrays.stream(filePaths).toList());

                // Remove potential duplicates between S3 and local file system
                names = new ArrayList<>(new HashSet<>(names));
                // The output must be in sorted (UTF-16, java's {@link String#compareTo}) order.
                names.sort(String::compareTo);
            }
        } catch (Exception e) {
            logger.warn("{}", e.toString());
        }
        System.out.printf("listAll " + names + "\n");
        return names.toArray(new String[]{});
    }

    @Override
    public void deleteFile(final String name) throws IOException {
        System.out.printf("deleteFile %s\n", name);

        if (Files.exists(super.getDirectory().resolve(name))) {
            super.deleteFile(name);
        } else {
            s3.deleteObject(b -> b.bucket(bucket).key(prefix + name));
        }
    }

    @Override
    public long fileLength(final String name) throws IOException {
        System.out.printf("fileLength %s\n", name);

        if (Files.exists(super.getDirectory().resolve(name))) {
            return super.fileLength(name);
        } else {
            return s3.headObject(b -> b.bucket(bucket).key(prefix + name)).contentLength();
        }
    }

    @Override
    public IndexOutput createOutput(final String name, final IOContext context) throws IOException {
        System.out.printf("createOutput %s\n", name);

        // Output always goes to local files first before sync to S3
        return super.createOutput(name, context);
    }

    @Override
    public void sync(final Collection<String> names) throws IOException {
        System.out.printf("sync %s\n", names);
        // Do nothing because syncMetadata() handles both durability and consistency of S3 data

        // Sync all the local files that have not been written to S3 yet
//        for (String name : names) {
//            if (name.contains(".lock")) {
//                // Do not sync lock file to S3
//                continue;
//            }
//            Path filePath = super.getDirectory().resolve(name);
//            if (Files.exists(filePath)) {
//                s3.putObject(b -> b.bucket(bucket).key(prefix + name), filePath);
//                super.deleteFile(name);
//            }
//        }
    }

    @Override
    public void rename(final String from, final String to) throws IOException {
        System.out.printf("rename %s -> %s\n", from, to);

        if (Files.exists(super.getDirectory().resolve(from))) {
            super.rename(from, to);
        } else {
            // Assume rename() is not called after syncMetadata() due to the Lucene's immutable nature
            try {
                s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(prefix + from).destinationBucket(bucket).destinationKey(prefix + to));
                s3.deleteObject(b -> b.bucket(bucket).key(prefix + from));
            } catch (Exception e) {
                logger.error(null, e);
            }
        }
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
        System.out.printf("createTempOutput %s %s\n", prefix, suffix);

        // Temp output does not need to sync to S3
        return super.createTempOutput(prefix, suffix, context);
    }

    @Override
    public void syncMetaData() throws IOException {
        System.out.print("syncMetaData\n");

        // This is called for sync directory node,
        // so we need to sync all the local files that have not been written to S3 yet
        String[] names = super.listAll();
        for (String name : names) {
            if (name.contains(".lock")) {
                // Do not sync lock file to S3
                continue;
            }
            System.out.printf("syncMetaData %s\n", name);
            Path filePath = super.getDirectory().resolve(name);
            // How about temp output?
            s3.putObject(b -> b.bucket(bucket).key(prefix + name), filePath);
            // This file is no more in local file system
            super.deleteFile(name);
        }
    }

    @Override
    public void close() throws IOException {
        System.out.print("close\n");

        super.close();
        // Sync all the local files that have not been written to S3 yet
//        String[] names = super.listAll();
//        for (String name : names) {
//            if (name.contains(".lock")) {
//                // Do not sync lock file to S3
//                continue;
//            }
//            Path filePath = super.getDirectory().resolve(name);
//            // How about temp output?
//            s3.putObject(b -> b.bucket(bucket).key(prefix + name), filePath);
//            // This file is no more in local file system
//            super.deleteFile(name);
//        }
    }

    @Override
    public IndexInput openInput(final String name, final IOContext context) throws IOException {
        System.out.printf("openInput %s\n", name);

        if (Files.exists(super.getDirectory().resolve(name))) {
            return super.openInput(name, context);
        } else {
            return new S3IndexInput(name, this);
        }
    }

    /**
     * *********************************************************************************************
     * Setter/getter methods
     * *********************************************************************************************
     */
    public String getBucket() {
        return bucket;
    }

    public String getPrefix() {
        return prefix;
    }

    public S3Client getS3() {
        return s3;
    }

    public Path getCachePath() {
        return cachePath;
    }

    @Override
    public Set<String> getPendingDeletions() throws IOException {
        return Collections.emptySet();
    }
}
