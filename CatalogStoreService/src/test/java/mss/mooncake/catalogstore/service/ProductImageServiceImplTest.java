package mss.mooncake.catalogstore.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import javax.imageio.ImageIO;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.service.impl.ProductImageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceImplTest {
    @TempDir Path root;
    @Mock ProductRepository products;
    @Mock StoreAccessService access;
    ProductImageServiceImpl images;

    @BeforeEach
    void setup() {
        images = new ProductImageServiceImpl(products, access, root.resolve("uploads").toString(), "https://api.example.test/");
    }

    private void productExists() {
        var product = new ProductEntity();
        product.setStoreId(7L);
        when(products.findById(5L)).thenReturn(Optional.of(product));
    }

    private byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void storesDecodedImageWithRandomNameAndSurvivesServiceRestart(String format) throws Exception {
        productExists();
        byte[] pixels = image(format, 40, 30);
        byte[] payload = "<script>untrusted trailing bytes</script>".getBytes(StandardCharsets.UTF_8);
        var bytes = new ByteArrayOutputStream();
        bytes.write(pixels);
        bytes.write(payload);
        var file = new MockMultipartFile("file", "../../target/evil.svg", "image/" + format, bytes.toByteArray());
        var response = images.upload(5L, 10L, file);
        var second = images.upload(5L, 10L, file);

        assertNotEquals(response.url(), second.url());
        assertTrue(response.url().matches("https://api\\.example\\.test/uploads/catalog/[a-f0-9-]{36}\\.(png|jpg)"));
        assertEquals(40, response.width());
        assertEquals(30, response.height());
        assertEquals("image/" + format, response.contentType());
        var restarted = new ProductImageServiceImpl(products, access, root.resolve("uploads").toString(), "https://api.example.test");
        var stored = restarted.load(response.url().substring(response.url().lastIndexOf('/') + 1));
        byte[] saved = stored.getContentAsByteArray();
        assertEquals(saved.length, response.size());
        assertFalse(new String(saved, StandardCharsets.ISO_8859_1).contains("<script>"));
        assertEquals(40, ImageIO.read(new ByteArrayInputStream(saved)).getWidth());
        verify(access, times(2)).requireOwnedStore(7L, 10L);
    }

    @Test
    void checksOwnerBeforeReadingOrWritingFiles() {
        productExists();
        when(access.requireOwnedStore(7L, 11L)).thenThrow(new ForbiddenException("Not owner"));
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        assertThrows(ForbiddenException.class, () -> images.upload(5L, 11L, file));
        verifyNoInteractions(file);
        assertFalse(Files.exists(root.resolve("uploads")));
    }

    @Test
    void missingProductAndMissingUserCannotUpload() {
        assertThrows(ForbiddenException.class, () -> images.upload(5L, null, null));
        when(products.findById(5L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> images.upload(5L, 10L, null));
        verifyNoInteractions(access);
    }

    @Test
    void rejectsSpoofedMimeSvgCorruptionEmptyAndOversizedFiles() throws Exception {
        productExists();
        assertThrows(BadRequestException.class, () -> images.upload(5L, 10L,
                new MockMultipartFile("file", "image.jpg", "image/jpeg", image("png", 2, 2))));
        for (String mime : Arrays.asList("image/svg+xml", "image/png", null)) {
            assertThrows(BadRequestException.class, () -> images.upload(5L, 10L,
                    new MockMultipartFile("file", "image.png", mime, "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes())));
        }
        byte[] truncated = Arrays.copyOf(image("png", 2, 2), 40);
        assertThrows(BadRequestException.class, () -> images.upload(5L, 10L,
                new MockMultipartFile("file", "image.png", "image/png", truncated)));
        for (int size : new int[] {0, ProductImageServiceImpl.MAX_BYTES + 1}) {
            assertThrows(BadRequestException.class, () -> images.upload(5L, 10L,
                    new MockMultipartFile("file", "image.png", "image/png", new byte[size])));
        }
        assertFalse(Files.exists(root.resolve("uploads")));
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecodingPixels() throws Exception {
        productExists();
        assertThrows(BadRequestException.class, () -> images.upload(5L, 10L,
                new MockMultipartFile("file", "wide.png", "image/png", image("png", 4097, 1))));
        assertThrows(BadRequestException.class, () -> images.upload(5L, 10L,
                new MockMultipartFile("file", "large.png", "image/png", image("png", 4096, 4096))));
        assertFalse(Files.exists(root.resolve("uploads")));
    }

    @Test
    void boundsActualStreamEvenIfReportedFileSizeIsWrong() {
        productExists();
        var file = spy(new MockMultipartFile("file", "large.png", "image/png",
                new byte[ProductImageServiceImpl.MAX_BYTES + 1]));
        doReturn(1L).when(file).getSize();
        assertThrows(BadRequestException.class, () -> images.upload(5L, 10L, file));
        assertFalse(Files.exists(root.resolve("uploads")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/uploads/catalog", "file:///tmp/uploads", "https://api.example.test?bad=true", "https://user:password@api.example.test"})
    void publicImageBaseMustBeAnAbsoluteGatewayHttpUrl(String baseUrl) {
        assertThrows(IllegalArgumentException.class, () -> new ProductImageServiceImpl(products, access,
                root.resolve("uploads").toString(), baseUrl));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.png", "..\\secret.png", "/etc/passwd", "image.svg", "%2e%2e%2fsecret.png", "00000000-0000-0000-0000-000000000000.png"})
    void refusesTraversalUnsupportedNamesAndMissingImages(String filename) {
        assertThrows(ResourceNotFoundException.class, () -> images.load(filename));
    }

    @Test
    void refusesBuildAndSourceDirectories() {
        for (String directory : new String[] {"src", "target"}) {
            assertThrows(IllegalArgumentException.class, () -> new ProductImageServiceImpl(products, access,
                    root.resolve(directory).resolve("uploads").toString(), "https://api.example.test"));
        }
    }
}
