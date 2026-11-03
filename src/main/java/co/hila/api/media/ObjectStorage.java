package co.hila.api.media;

import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class ObjectStorage {
  final Path directory;
  final S3Client s3;
  final String bucket;

  public ObjectStorage(
      @Value("${hila.storage.provider}") String provider,
      @Value("${hila.storage.directory}") String dir,
      @Value("${hila.storage.endpoint}") String endpoint,
      @Value("${hila.storage.bucket}") String bucket,
      @Value("${hila.storage.access-key}") String access,
      @Value("${hila.storage.secret-key}") String secret)
      throws Exception {
    directory = Path.of(dir).toAbsolutePath().normalize();
    this.bucket = bucket;
    if (provider.equals("r2")) {
      if (endpoint.isBlank() || bucket.isBlank() || access.isBlank() || secret.isBlank())
        throw new IllegalStateException("R2 configuration is incomplete");
      s3 =
          S3Client.builder()
              .endpointOverride(java.net.URI.create(endpoint))
              .region(Region.of("auto"))
              .credentialsProvider(
                  StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
              .serviceConfiguration(
                  S3Configuration.builder()
                      .pathStyleAccessEnabled(true)
                      .chunkedEncodingEnabled(false)
                      .build())
              .build();
    } else if (provider.equals("local")) {
      s3 = null;
      Files.createDirectories(directory);
    } else throw new IllegalStateException("Unknown storage provider");
  }

  Path path(String key) {
    Path p = directory.resolve(key).normalize();
    if (!p.startsWith(directory)) throw new IllegalArgumentException("Invalid object key");
    return p;
  }

  public void put(String key, byte[] data, String type) throws Exception {
    if (s3 == null) Files.write(path(key), data, StandardOpenOption.CREATE_NEW);
    else
      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(),
          RequestBody.fromBytes(data));
  }

  public byte[] get(String key) throws Exception {
    return s3 == null
        ? Files.readAllBytes(path(key))
        : s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .asByteArray();
  }

  public void delete(String key) throws Exception {
    if (s3 == null) Files.deleteIfExists(path(key));
    else s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
