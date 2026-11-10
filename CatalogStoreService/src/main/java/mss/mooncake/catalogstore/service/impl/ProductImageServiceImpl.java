package mss.mooncake.catalogstore.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import mss.mooncake.catalogstore.dto.response.ProductImageResponse;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.service.ProductImageService;
import mss.mooncake.catalogstore.service.StoreAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductImageServiceImpl implements ProductImageService {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16_000_000;
    private final ProductRepository products;
    private final StoreAccessService access;
    private final Path directory;
    private final String publicBaseUrl;

    public ProductImageServiceImpl(ProductRepository products, StoreAccessService access,
            @Value("${app.catalog-images.directory:${user.home}/.mooncake/uploads/catalog}") String directory,
            @Value("${app.catalog-images.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.products = products;
        this.access = access;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        for (Path part : this.directory) {
            if (part.toString().equalsIgnoreCase("src") || part.toString().equalsIgnoreCase("target")) {
                throw new IllegalArgumentException("Catalog images must be stored outside src/target");
            }
        }
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        URI base = URI.create(this.publicBaseUrl);
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Catalog public base URL must be an absolute HTTP(S) Gateway URL");
        }
    }

    @Override
    public ProductImageResponse upload(Long productId, Long userId, MultipartFile file) {
        if (userId == null) throw new ForbiddenException("Vui lòng đăng nhập để tải ảnh.");
        var product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        // Rechecks supplier verification, store ownership and suspension before any file is written.
        access.requireOwnedStore(product.getStoreId(), userId);
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BadRequestException("Ảnh phải có dung lượng từ 1 byte đến 5 MiB.");
        }

        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new BadRequestException("Không đọc được ảnh tải lên.");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BadRequestException("Ảnh phải có dung lượng từ 1 byte đến 5 MiB.");
        }

        BufferedImage image;
        String format;
        String contentType;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                contentType = switch (format) {
                    case "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    default -> throw invalidImage();
                };
                if (!contentType.equalsIgnoreCase(file.getContentType())) throw invalidImage();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new BadRequestException("Ảnh tối đa 4096 × 4096 và 16 triệu điểm ảnh.");
                }
                image = reader.read(0);
                if (image == null) throw invalidImage();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw invalidImage();
        }

        // Re-encode decoded pixels: never publish the original bytes, filename or embedded payloads.
        String filename = UUID.randomUUID() + (format.equals("jpeg") ? ".jpg" : ".png");
        try {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) throw invalidImage();
            if (output.size() > MAX_BYTES) throw new BadRequestException("Ảnh sau xử lý vượt quá 5 MiB.");
            Files.createDirectories(directory);
            Path target = directory.resolve(filename);
            try {
                Files.write(target, output.toByteArray(), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ex) {
                throw ex;
            } catch (IOException ex) {
                Files.deleteIfExists(target);
                throw ex;
            }
            return new ProductImageResponse(publicBaseUrl + "/uploads/catalog/" + filename,
                    contentType, image.getWidth(), image.getHeight(), output.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể lưu ảnh. Vui lòng thử lại sau.", ex);
        }
    }

    @Override
    public Resource load(String filename) {
        if (filename == null || !filename.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(jpg|png)")) {
            throw new ResourceNotFoundException("Không tìm thấy ảnh.");
        }
        Path target = directory.resolve(filename).normalize();
        if (!target.getParent().equals(directory) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResourceNotFoundException("Không tìm thấy ảnh.");
        }
        return new FileSystemResource(target);
    }

    private static BadRequestException invalidImage() {
        return new BadRequestException("Chỉ nhận ảnh JPEG hoặc PNG hợp lệ, đúng Content-Type; không hỗ trợ SVG.");
    }
}
