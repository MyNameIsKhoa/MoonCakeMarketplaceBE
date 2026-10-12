package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoreRequest(
    @NotBlank(message = "Tên store không được để trống")
    @Size(max = 255, message = "Tên store tối đa 255 ký tự")
    String name,

    @Size(max = 500, message = "URL logo tối đa 500 ký tự")
    String logoUrl,

    @Size(max = 500, message = "URL banner tối đa 500 ký tự")
    String bannerUrl,

    @Size(max = 100, message = "Theme key tối đa 100 ký tự")
    String themeKey
) { }
