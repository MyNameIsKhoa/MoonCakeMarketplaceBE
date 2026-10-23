package mss.mooncake.catalogstore.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mss.mooncake.catalogstore.dto.request.StockReservationRequest;
import mss.mooncake.catalogstore.dto.response.StockReservationResponse;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StockReservationEntity;
import mss.mooncake.catalogstore.entity.StockReservationLineEntity;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StockReservationLineRepository;
import mss.mooncake.catalogstore.repository.StockReservationRepository;
import mss.mooncake.catalogstore.service.CatalogApplicationService;
import mss.mooncake.catalogstore.service.StockReservationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockReservationServiceImpl implements StockReservationService {
    private final StockReservationRepository reservations;
    private final StockReservationLineRepository lines;
    private final ProductVariantRepository variants;
    private final CatalogApplicationService catalog;

    public StockReservationServiceImpl(StockReservationRepository reservations,
            StockReservationLineRepository lines, ProductVariantRepository variants,
            CatalogApplicationService catalog) {
        this.reservations = reservations;
        this.lines = lines;
        this.variants = variants;
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public StockReservationResponse reserve(StockReservationRequest request) {
        Map<Long, Integer> requested = new HashMap<>();
        for (var line : request.lines()) {
            if (requested.putIfAbsent(line.variantId(), line.quantity()) != null) {
                throw new BadRequestException("Duplicate variantId in reservation");
            }
        }
        LocalDateTime expiresAt = request.expiresAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                .truncatedTo(ChronoUnit.SECONDS);
        var existing = reservations.findById(request.reservationKey());
        if (existing.isPresent()) {
            var saved = existing.get();
            Map<Long, Integer> savedLines = lines.findByReservationKeyOrderByVariantId(request.reservationKey())
                    .stream().collect(Collectors.toMap(StockReservationLineEntity::getVariantId,
                            StockReservationLineEntity::getQuantity));
            if (!saved.getExpiresAt().equals(expiresAt) || !savedLines.equals(requested)) {
                throw new ConflictException("Reservation key already used with different payload");
            }
            return response(saved);
        }
        if (!expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new BadRequestException("Reservation expiry must be in the future");
        }
        StockReservationEntity reservation = new StockReservationEntity(request.reservationKey(), expiresAt);
        try {
            reservations.saveAndFlush(reservation); // unique key serializes retries, including disjoint variants
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Reservation is being created; retry the same request");
        }

        List<Long> ids = requested.keySet().stream().sorted().toList();
        List<ProductVariantEntity> locked = variants.lockByIds(ids);
        if (locked.size() != ids.size()) {
            throw new ResourceNotFoundException("Variant not found");
        }
        var snapshots = catalog.getVariantSnapshots(ids);
        Set<Long> active = snapshots.stream()
                .filter(s -> "ACTIVE".equals(s.variantStatus()) && "ACTIVE".equals(s.productStatus())
                        && "ACTIVE".equals(s.storeStatus()))
                .map(s -> s.variantId()).collect(Collectors.toSet());
        if (active.size() != ids.size()) {
            throw new ConflictException("Product or store is not active");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (var variant : locked) {
            long held = lines.heldQuantity(variant.getId(), now);
            if ((long) variant.getStockQuantity() - held < requested.get(variant.getId())) {
                throw new ConflictException("Insufficient stock for variant " + variant.getId());
            }
        }
        lines.saveAll(ids.stream().map(id ->
                new StockReservationLineEntity(request.reservationKey(), id, requested.get(id))).toList());
        return response(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public StockReservationResponse get(String reservationKey) {
        return response(reservations.findById(reservationKey)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found")));
    }

    @Override
    @Transactional
    public StockReservationResponse confirm(String reservationKey) {
        StockReservationEntity reservation = reservations.lockByKey(reservationKey)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found"));
        if ("CONFIRMED".equals(reservation.getStatus())) return response(reservation);
        if (!"HELD".equals(reservation.getStatus()) ||
                !reservation.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new ConflictException("Reservation is no longer confirmable");
        }
        var reserved = lines.findByReservationKeyOrderByVariantId(reservationKey);
        var locked = variants.lockByIds(reserved.stream().map(StockReservationLineEntity::getVariantId).toList());
        Map<Long, ProductVariantEntity> byId = locked.stream()
                .collect(Collectors.toMap(ProductVariantEntity::getId, v -> v));
        for (var line : reserved) {
            var variant = byId.get(line.getVariantId());
            if (variant == null || variant.getStockQuantity() < line.getQuantity()) {
                throw new ConflictException("Reserved stock became unavailable");
            }
            variant.setStockQuantity(variant.getStockQuantity() - line.getQuantity());
        }
        reservation.setStatus("CONFIRMED");
        return response(reservation);
    }

    @Override
    @Transactional
    public StockReservationResponse release(String reservationKey) {
        StockReservationEntity reservation = reservations.lockByKey(reservationKey)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found"));
        if ("CONFIRMED".equals(reservation.getStatus())) {
            throw new ConflictException("Confirmed reservation cannot be released");
        }
        reservation.setStatus("RELEASED");
        return response(reservation);
    }

    private StockReservationResponse response(StockReservationEntity reservation) {
        String effectiveStatus = "HELD".equals(reservation.getStatus())
                && !reservation.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
                ? "EXPIRED" : reservation.getStatus();
        return new StockReservationResponse(reservation.getReservationKey(), effectiveStatus,
                reservation.getExpiresAt().atOffset(ZoneOffset.UTC),
                lines.findByReservationKeyOrderByVariantId(reservation.getReservationKey()).stream()
                        .map(line -> new StockReservationResponse.Line(line.getVariantId(), line.getQuantity())).toList());
    }
}
