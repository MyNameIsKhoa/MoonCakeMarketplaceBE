package mss.mooncake.catalogstore.controller;

import java.util.List;
import mss.mooncake.catalogstore.dto.response.StoreResponse;
import mss.mooncake.catalogstore.service.StoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalog")
public class AdminStoreController {
    private final StoreService stores;

    public AdminStoreController(StoreService stores) { this.stores = stores; }

    @GetMapping("/stores")
    public List<StoreResponse> listStores() { return stores.getAllStoresForAdmin(); }
}
