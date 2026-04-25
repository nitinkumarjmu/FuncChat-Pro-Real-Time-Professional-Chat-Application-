package com.chatapp.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralised immutable application configuration.
 * Extended in Phase 2 with AWS S3 and webhook settings.
 *
 * Functional principle: loaded once, all accessors are pure getters.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final AppConfig INSTANCE = new AppConfig();

    // Server
    private final int    serverPort;
    private final int    webhookPort;
    private final int    maxConnections;
    private final int    messageHistoryLimit;

    // Firebase
    private final String firebaseProjectId;
    private final String firebaseApiKey;
    private final String firebaseDatabaseUrl;

    // AWS S3 (Phase 2)
    private final String s3BucketName;
    private final String s3Region;
    private final long   maxFileSizeBytes;
    private final int    presignedUrlExpiryMinutes;
    private final String s3AccessKey;
    private final String s3SecretKey;
    private final boolean mockAuth;

    private AppConfig() {
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();

        this.serverPort                 = parseInt(env, "SERVER_PORT", "8080");
        this.webhookPort                = parseInt(env, "WEBHOOK_PORT", "8081");
        this.maxConnections             = parseInt(env, "MAX_CONNECTIONS", "200");
        this.messageHistoryLimit        = parseInt(env, "MESSAGE_HISTORY_LIMIT", "100");

        this.firebaseProjectId          = env.get("FIREBASE_PROJECT_ID",   "your-project-id");
        this.firebaseApiKey             = env.get("FIREBASE_API_KEY",       "your-api-key");
        this.firebaseDatabaseUrl        = env.get("FIREBASE_DATABASE_URL",
                "https://your-project-id-default-rtdb.firebaseio.com");

        this.s3BucketName               = env.get("S3_BUCKET_NAME",         "funcchat-files");
        this.s3Region                   = env.get("S3_REGION",               "ap-south-1");
        this.maxFileSizeBytes           = parseLong(env, "MAX_FILE_SIZE_BYTES", String.valueOf(5 * 1024 * 1024));
        this.presignedUrlExpiryMinutes  = parseInt(env, "PRESIGNED_URL_EXPIRY_MINUTES", "15");
        this.s3AccessKey                = env.get("S3_ACCESS_KEY",          "your-access-key");
        this.s3SecretKey                = env.get("S3_SECRET_KEY",          "your-secret-key");
        this.mockAuth                   = Boolean.parseBoolean(env.get("MOCK_AUTH", "false"));


        log.info("Config loaded — port={}, webhookPort={}, project={}",
                serverPort, webhookPort, firebaseProjectId);
    }

    private static int  parseInt(Dotenv env, String key, String def)  { return Integer.parseInt(env.get(key, def)); }
    private static long parseLong(Dotenv env, String key, String def) { return Long.parseLong(env.get(key, def)); }

    public static AppConfig get()                   { return INSTANCE; }

    public int    getServerPort()                   { return serverPort; }
    public int    getWebhookPort()                  { return webhookPort; }
    public int    getMaxConnections()               { return maxConnections; }
    public int    getMessageHistoryLimit()          { return messageHistoryLimit; }
    public String getFirebaseProjectId()            { return firebaseProjectId; }
    public String getFirebaseApiKey()               { return firebaseApiKey; }
    public String getFirebaseDatabaseUrl()          { return firebaseDatabaseUrl; }
    public String getS3BucketName()                 { return s3BucketName; }
    public String getS3Region()                     { return s3Region; }
    public long   getMaxFileSizeBytes()             { return maxFileSizeBytes; }
    public int    getPresignedUrlExpiryMinutes()    { return presignedUrlExpiryMinutes; }
    public String getS3AccessKey()                  { return s3AccessKey; }
    public String getS3SecretKey()                  { return s3SecretKey; }
    public boolean isMockAuth()                     { return mockAuth; }
}
