package com.avangrid.gui.avangrid_backend.repository;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;


import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;


@Repository
@Slf4j
public class AzureBlobRepository {

    @Value("${azure.storage.account-name}")
    private String storageAccountName;

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Value("${azure.client-id}")
    private String clientId;

    @Value("${azure.client-secret}")
    private String clientSecret;

    @Value("${azure.tenant-id}")
    private String tenantId;

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient containerClient;


    private BlobServiceClient getBlobServiceClient() {
        if (blobServiceClient == null) {
            // Create Service Principal credential
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

            // Build the blob service client with Service Principal authentication
            String endpoint = String.format("https://%s.blob.core.windows.net", storageAccountName);
            
            blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
        }
        return blobServiceClient;
    }

    private BlobContainerClient getContainerClient() {
        if (containerClient == null) {
            containerClient = getBlobServiceClient().getBlobContainerClient(containerName);
        }
        return containerClient;
    }

    public List<String> listBlobs(String prefix) {
        List<String> blobNames = new ArrayList<>();
        try {
            for (BlobItem blobItem : getContainerClient().listBlobsByHierarchy(prefix)) {
                blobNames.add(blobItem.getName());
            }
        } catch (Exception e) {
            log.info("Error listing blobs: " + e.getMessage());
        }
        return blobNames;
    }

    public byte[] getBlobContent(String blobName) {
        try {
            BlobClient blobClient = getContainerClient().getBlobClient(blobName);
            return blobClient.downloadContent().toBytes();
        } catch (Exception e) {
            log.info("Error downloading blob: " + e.getMessage());
            return new byte[0];
        }
    }

    public boolean isContainerAvailable() {
    try {
        return getContainerClient().exists();
    } catch (Exception e) {
        log.info("Azure Blob connection check failed: " + e.getMessage());
        return false;
    }
    }

    public InputStream getBlobStream(String blobName) {
        BlobClient blobClient = getContainerClient().getBlobClient(blobName);
        return blobClient.openInputStream();
    }


}