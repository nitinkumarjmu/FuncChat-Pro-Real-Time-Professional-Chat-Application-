package com.chatapp.service;

import com.chatapp.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * File sharing service using AWS S3 with pre-signed URL delivery.
 *
 * Functional principles applied:
 *   - Predicate<Long> for file size validation
 *   - Predicate<String> for allowed file type validation
 *   - CompletableFuture for async upload
 *   - Optional for nullable error returns
 *   - Pure validation functions composable via .and()
 *
 * Security:
 *   - Files stored privately in S3 (no public access)
 *   - Delivered via pre-signed URLs valid for N minutes (configurable)
 *   - File type whitelist enforced server-side (not trusting client)
 */
public final class FileShareService {

    private static final Logger log = LoggerFactory.getLogger(FileShareService.class);

    // ── Reusable predicates (pure) ────────────────────────────────────────────

    /** Predicate: file is within configured size limit (default 5 MB).
     *  A value of 0 means the client did not send the size — treated as unknown,
     *  so the pre-check is skipped and the actual byte-array size is checked in upload(). */
    public static final Predicate<Long> WITHIN_SIZE_LIMIT =
            bytes -> bytes == 0 || (bytes > 0 && bytes <= AppConfig.get().getMaxFileSizeBytes());

    /** Allowed file extensions (whitelist) */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp",    // images
            "pdf", "txt", "md",                      // documents
            "zip", "tar",                            // archives
            "mp4", "mov"                             // short videos (optional)
    );

    /** Predicate: file extension is in whitelist */
    public static final Predicate<String> ALLOWED_TYPE = fileName -> {
        if (fileName == null || !fileName.contains(".")) return false;
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return ALLOWED_EXTENSIONS.contains(ext);
    };

    /** Predicate: no null bytes or path traversal in filename */
    public static final Predicate<String> SAFE_FILENAME =
            name -> name != null && !name.contains("..") && !name.contains("/")
                    && !name.contains("\\") && name.length() <= 255;

    /** Composed: file passes all validation */
    public static final Predicate<String> VALID_FILENAME =
            ALLOWED_TYPE.and(SAFE_FILENAME);

    // ── State ─────────────────────────────────────────────────────────────────
    private final AppConfig  config;
    private final S3Client   s3Client;
    private final S3Presigner presigner;

    public FileShareService() {
        this.config   = AppConfig.get();
        Region region = Region.of(config.getS3Region());
        // Use static credentials from .env
        software.amazon.awssdk.auth.credentials.StaticCredentialsProvider creds = 
            software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                    config.getS3AccessKey(), config.getS3SecretKey()));

        this.s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(creds)
                .build();

        this.presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(creds)
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Upload a file to S3 and return a pre-signed download URL.
     *
     * Pipeline (functional):
     *   validate size → validate type → validate filename
     *   → generate unique S3 key → upload → generate pre-signed URL → return URL
     *
     * @param fileName  Original filename (e.g. "report.pdf")
     * @param data      Raw file bytes
     * @return          CompletableFuture<String> with the pre-signed URL
     */
    public CompletableFuture<String> upload(String fileName, byte[] data) {
        // Validate — all checks before any IO
        if (!WITHIN_SIZE_LIMIT.test((long) data.length))
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    String.format("File exceeds limit of %d MB",
                            config.getMaxFileSizeBytes() / (1024 * 1024))));

        if (!VALID_FILENAME.test(fileName))
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "File type not allowed or filename is unsafe: " + fileName));

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build a unique, collision-resistant S3 key
                String ext = fileName.substring(fileName.lastIndexOf('.'));
                String key = "uploads/" + UUID.randomUUID() + "_" + sanitiseKey(fileName);

                // Detect content type
                String contentType = resolveContentType(ext);

                // Upload to S3
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(config.getS3BucketName())
                                .key(key)
                                .contentType(contentType)
                                .contentLength((long) data.length)
                                .build(),
                        RequestBody.fromBytes(data));

                // Generate pre-signed URL
                PresignedGetObjectRequest presigned = presigner.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofMinutes(
                                        config.getPresignedUrlExpiryMinutes()))
                                .getObjectRequest(r -> r
                                        .bucket(config.getS3BucketName())
                                        .key(key))
                                .build());

                String url = presigned.url().toString();
                log.info("File uploaded: {} ({} bytes) → key={}", fileName, data.length, key);
                return url;

            } catch (Exception e) {
                log.error("S3 upload failed for {}: {}", fileName, e.getMessage());
                throw new RuntimeException("File upload failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Upload a Base64-encoded file (received via WebSocket).
     * Decodes, validates, and delegates to upload().
     */
    public CompletableFuture<String> uploadBase64(String fileName, String base64Data) {
        try {
            byte[] data = Base64.getDecoder().decode(base64Data);
            return upload(fileName, data);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid Base64 encoding"));
        }
    }

    /**
     * Validate a file without uploading it.
     * Returns Optional<String> with the error message, empty if valid.
     */
    public Optional<String> validate(String fileName, long fileSizeBytes) {
        if (!WITHIN_SIZE_LIMIT.test(fileSizeBytes))
            return Optional.of(String.format("File exceeds limit of %d MB",
                    config.getMaxFileSizeBytes() / (1024 * 1024)));
        if (!VALID_FILENAME.test(fileName))
            return Optional.of("File type not allowed: " + fileName);
        return Optional.empty();
    }

    // ── Pure helpers ──────────────────────────────────────────────────────────

    /** Remove characters not safe for S3 keys */
    private static String sanitiseKey(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String resolveContentType(String ext) {
        return switch (ext.toLowerCase()) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png"          -> "image/png";
            case ".gif"          -> "image/gif";
            case ".webp"         -> "image/webp";
            case ".pdf"          -> "application/pdf";
            case ".txt"          -> "text/plain";
            case ".md"           -> "text/markdown";
            case ".zip"          -> "application/zip";
            case ".mp4"          -> "video/mp4";
            case ".mov"          -> "video/quicktime";
            default              -> "application/octet-stream";
        };
    }

    public void shutdown() {
        s3Client.close();
        presigner.close();
    }
}
