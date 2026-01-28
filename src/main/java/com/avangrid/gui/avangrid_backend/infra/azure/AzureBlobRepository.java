package com.avangrid.gui.avangrid_backend.infra.azure;

import com.azure.core.exception.AzureException;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import com.avangrid.gui.avangrid_backend.exception.AzureBlobRepositoryException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Repository for Azure Blob Storage operations.
 * Handles connection and data retrieval from Azure Blob Storage.
 */
@Repository
@Slf4j
public class AzureBlobRepository {

    private static final String BLOB_ENDPOINT_TEMPLATE = "https://%s.blob.core.windows.net";

    private final String storageAccountName;
    private final String clientId;
    private final String clientSecret;
    private final String tenantId;
    private final String containerName;

    private final BlobServiceClient blobServiceClient;
    private final BlobContainerClient containerClient;

    // Constructor with all dependencies
    public AzureBlobRepository(
            @Value("${azure.storage.account-name}") String storageAccountName,
            @Value("${azure.storage.container-name}") String containerName,
            @Value("${azure.client-id}") String clientId,
            @Value("${azure.client-secret}") String clientSecret,
            @Value("${azure.tenant-id}") String tenantId) {

        this.storageAccountName = storageAccountName;
        this.containerName = containerName;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tenantId = tenantId;

        // Initialize clients in constructor
        this.blobServiceClient = createBlobServiceClient();
        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);

        log.info("Azure Blob Storage client initialized for container: {}", containerName);
    }

    private BlobServiceClient createBlobServiceClient() {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

        String endpoint = String.format(BLOB_ENDPOINT_TEMPLATE, storageAccountName);

        return new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
    }

    public List<String> listBlobs(String prefix) {
        List<String> blobNames = new ArrayList<>();

        try {
            for (BlobItem blobItem : containerClient.listBlobsByHierarchy(prefix)) {
                blobNames.add(blobItem.getName());
            }
            log.debug("Successfully listed {} blobs with prefix: {}", blobNames.size(), prefix);
            return blobNames;
        } catch (BlobStorageException e) {
            log.error("Error listing blobs with prefix: {}", prefix, e);
            throw new AzureBlobRepositoryException("Failed to list blobs with prefix: " + prefix, e);
        } catch (AzureException e) {
            log.error("Error listing blobs with prefix: {}", prefix, e);
            throw new AzureBlobRepositoryException("Azure service error while listing blobs", e);
        }
    }

    public byte[] getBlobContent(String blobName) {
        validateBlobName(blobName);

        try {
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            byte[] content = blobClient.downloadContent().toBytes();
            log.debug("Successfully downloaded blob: {} ({} bytes)", blobName, content.length);
            return content;
        } catch (BlobStorageException e) {
            log.error("Error downloading blob: {}", blobName, e);
            throw new AzureBlobRepositoryException("Failed to download blob: " + blobName, e);
        } catch (AzureException e) {
            log.error("Error downloading blob: {}", blobName, e);
            throw new AzureBlobRepositoryException("Azure service error while downloading blob", e);
        }
    }

    public boolean isContainerAvailable() {
        try {
            boolean exists = containerClient.exists();
            log.debug("Container availability check: {}", exists);
            return exists;
        } catch (Exception e) {
            log.warn("Error checking container availability", e);
            return false;
        }
    }

    public InputStream getBlobStream(String blobName) {
        validateBlobName(blobName);

        try {
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            InputStream stream = blobClient.openInputStream();
            log.debug("Successfully opened stream for blob: {}", blobName);
            return stream;
        } catch (BlobStorageException e) {
            log.error("Error opening stream for blob: {}", blobName, e);
            throw new AzureBlobRepositoryException("Failed to open stream for blob: " + blobName, e);
        } catch (AzureException e) {
            log.error("Error opening stream for blob: {}", blobName, e);
            throw new AzureBlobRepositoryException("Azure service error while opening blob stream", e);
        }
    }

    private void validateBlobName(String blobName) {
        if (blobName == null || blobName.trim().isEmpty()) {
            throw new IllegalArgumentException("Blob name cannot be null or empty");
        }
    }


}