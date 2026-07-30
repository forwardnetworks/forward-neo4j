package com.forwardnetworks.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForwardConfigTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsProtectedFourLineCredentialFile() throws IOException {
        Path credentialFile = temporaryDirectory.resolve("forward.credentials");
        Files.writeString(credentialFile, "client-id\nclient-secret\nfwd.example\nnetwork-1\n");
        Files.setPosixFilePermissions(credentialFile, PosixFilePermissions.fromString("rw-------"));
        Properties properties = new Properties();
        properties.setProperty("forward.credentialFile", credentialFile.toString());

        ForwardConfig config = ForwardConfig.from(Map.of(), properties);

        assertEquals("https://fwd.example", config.baseUri().toString());
        assertEquals("client-id", config.username());
        assertEquals("client-secret", config.password());
        assertEquals("network-1", config.resolveNetworkId(""));
        assertEquals(ForwardLicenseTier.B, config.licenseTier());
    }

    @Test
    void rejectsCredentialFileReadableByOtherUsers() throws IOException {
        Path credentialFile = temporaryDirectory.resolve("forward.credentials");
        Files.writeString(credentialFile, "client-id\nclient-secret\nfwd.example\n");
        Files.setPosixFilePermissions(credentialFile, PosixFilePermissions.fromString("rw-r--r--"));
        Properties properties = new Properties();
        properties.setProperty("forward.credentialFile", credentialFile.toString());

        assertThrows(IllegalStateException.class, () -> ForwardConfig.from(Map.of(), properties));
    }

    @Test
    void parsesExplicitLicenseTierAndFeatureFacets() throws IOException {
        Path credentialFile = temporaryDirectory.resolve("forward.credentials");
        Files.writeString(credentialFile, "client-id\nclient-secret\nfwd.example\n");
        Files.setPosixFilePermissions(credentialFile, PosixFilePermissions.fromString("rw-------"));
        Properties properties = new Properties();
        properties.setProperty("forward.credentialFile", credentialFile.toString());
        properties.setProperty("forward.licenseTier", "nsp");

        ForwardConfig config = ForwardConfig.from(Map.of(), properties);

        assertEquals(ForwardLicenseTier.NSP, config.licenseTier());
        assertEquals(true, config.licenseTier().network());
        assertEquals(true, config.licenseTier().security());
        assertEquals(true, config.licenseTier().predict());
    }
}
