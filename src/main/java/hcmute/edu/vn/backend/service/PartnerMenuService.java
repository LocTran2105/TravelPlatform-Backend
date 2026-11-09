package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.MenuCategoryRequest;
import hcmute.edu.vn.backend.dto.MenuItemRequest;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerMenuCategory;
import hcmute.edu.vn.backend.entity.PartnerMenuItem;
import hcmute.edu.vn.backend.entity.PartnerMenuItemPrice;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerMenuCategoryRepository;
import hcmute.edu.vn.backend.repository.PartnerMenuItemPriceRepository;
import hcmute.edu.vn.backend.repository.PartnerMenuItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PartnerMenuService {

    private final PartnerBusinessRepository businessRepository;
    private final PartnerMenuCategoryRepository categoryRepository;
    private final PartnerMenuItemRepository itemRepository;
    private final PartnerMenuItemPriceRepository priceRepository;
    private final PartnerAccessService accessService;
    private final PartnerAuditService auditService;

    /**
     * Lấy danh mục menu.
     */
    @Transactional(readOnly = true)
    public List<PartnerMenuCategory> getCategories(
            Long businessId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        return categoryRepository
                .findByBusiness_IdOrderBySortOrderAscIdAsc(businessId);
    }

    /**
     * Tạo danh mục menu.
     */
    @Transactional
    public PartnerMenuCategory createCategory(
            Long businessId,
            MenuCategoryRequest request,
            String email
    ) {
        PartnerBusiness business =
                requireOwnedBusiness(businessId, email);

        validateCategoryRequest(request);

        PartnerMenuCategory category =
                new PartnerMenuCategory();

        category.setBusiness(business);

        applyCategory(category, request);

        PartnerMenuCategory saved =
                categoryRepository.save(category);

        auditService.log(
                email,
                businessId,
                "CREATE",
                "MENU_CATEGORY",
                saved.getId(),
                null,
                saved.getName()
        );

        return saved;
    }

    /**
     * Cập nhật danh mục menu.
     */
    @Transactional
    public PartnerMenuCategory updateCategory(
            Long businessId,
            Long categoryId,
            MenuCategoryRequest request,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        validateCategoryRequest(request);

        PartnerMenuCategory category =
                categoryRepository
                        .findByIdAndBusiness_Id(
                                categoryId,
                                businessId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy danh mục menu."
                                )
                        );

        String oldName = category.getName();

        applyCategory(category, request);

        PartnerMenuCategory saved =
                categoryRepository.save(category);

        auditService.log(
                email,
                businessId,
                "UPDATE",
                "MENU_CATEGORY",
                categoryId,
                oldName,
                saved.getName()
        );

        return saved;
    }

    /**
     * Ngừng sử dụng danh mục, không xóa vật lý.
     */
    @Transactional
    public void deleteCategory(
            Long businessId,
            Long categoryId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        PartnerMenuCategory category =
                categoryRepository
                        .findByIdAndBusiness_Id(
                                categoryId,
                                businessId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy danh mục menu."
                                )
                        );

        category.setActive(false);

        categoryRepository.save(category);

        auditService.log(
                email,
                businessId,
                "DEACTIVATE",
                "MENU_CATEGORY",
                categoryId,
                category.getName(),
                null
        );
    }

    /**
     * Lấy danh sách món ăn/dịch vụ.
     */
    @Transactional(readOnly = true)
    public List<PartnerMenuItem> getItems(
            Long businessId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        return itemRepository
                .findByCategory_Business_IdOrderBySortOrderAscIdAsc(
                        businessId
                );
    }

    /**
     * Tạo món ăn/dịch vụ.
     */
    @Transactional
    public PartnerMenuItem createItem(
            Long businessId,
            MenuItemRequest request,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        validateItemRequest(request);

        PartnerMenuCategory category =
                getOwnedCategory(
                        businessId,
                        request.getCategoryId()
                );

        PartnerMenuItem item = new PartnerMenuItem();

        item.setCategory(category);

        applyItem(item, request);

        PartnerMenuItem saved =
                itemRepository.save(item);

        savePriceHistory(
                saved,
                saved.getPrice(),
                saved.getCurrency(),
                request.getPriceNote()
        );

        auditService.log(
                email,
                businessId,
                "CREATE",
                "MENU_ITEM",
                saved.getId(),
                null,
                saved.getName()
        );

        return saved;
    }

    /**
     * Cập nhật món ăn/dịch vụ.
     */
    @Transactional
    public PartnerMenuItem updateItem(
            Long businessId,
            Long itemId,
            MenuItemRequest request,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        validateItemRequest(request);

        PartnerMenuItem item =
                itemRepository
                        .findByIdAndCategory_Business_Id(
                                itemId,
                                businessId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy món/dịch vụ."
                                )
                        );

        BigDecimal oldPrice = item.getPrice();
        String oldCurrency = item.getCurrency();
        String oldName = item.getName();

        Long categoryId = request.getCategoryId() == null
                ? item.getCategory().getId()
                : request.getCategoryId();

        item.setCategory(
                getOwnedCategory(businessId, categoryId)
        );

        applyItem(item, request);

        PartnerMenuItem saved =
                itemRepository.save(item);

        boolean priceChanged = oldPrice == null
                || oldPrice.compareTo(saved.getPrice()) != 0
                || !Objects.equals(
                oldCurrency,
                saved.getCurrency()
        );

        if (priceChanged) {
            savePriceHistory(
                    saved,
                    saved.getPrice(),
                    saved.getCurrency(),
                    request.getPriceNote()
            );
        }

        auditService.log(
                email,
                businessId,
                "UPDATE",
                "MENU_ITEM",
                itemId,
                oldName,
                saved.getName()
        );

        return saved;
    }

    /**
     * Ngừng bán món/dịch vụ.
     */
    @Transactional
    public void deactivateItem(
            Long businessId,
            Long itemId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        PartnerMenuItem item =
                itemRepository
                        .findByIdAndCategory_Business_Id(
                                itemId,
                                businessId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy món/dịch vụ."
                                )
                        );

        item.setAvailable(false);

        itemRepository.save(item);

        auditService.log(
                email,
                businessId,
                "DEACTIVATE",
                "MENU_ITEM",
                itemId,
                item.getName(),
                null
        );
    }

    /**
     * Lấy lịch sử thay đổi giá.
     */
    @Transactional(readOnly = true)
    public List<PartnerMenuItemPrice> getPriceHistory(
            Long businessId,
            Long itemId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        itemRepository
                .findByIdAndCategory_Business_Id(
                        itemId,
                        businessId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy món/dịch vụ."
                        )
                );

        return priceRepository
                .findByMenuItem_IdOrderByEffectiveFromDesc(itemId);
    }

    /**
     * Xác minh quyền sở hữu cơ sở kinh doanh.
     */
    private PartnerBusiness requireOwnedBusiness(
            Long businessId,
            String email
    ) {
        accessService.requireApprovedPartner(email);

        return businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(
                        businessId,
                        email
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh "
                                        + "hoặc bạn không có quyền quản lý."
                        )
                );
    }

    private PartnerMenuCategory getOwnedCategory(
            Long businessId,
            Long categoryId
    ) {
        if (categoryId == null) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn danh mục menu."
            );
        }

        return categoryRepository
                .findByIdAndBusiness_Id(
                        categoryId,
                        businessId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Danh mục không thuộc cơ sở kinh doanh này."
                        )
                );
    }

    /**
     * Lưu lịch sử giá.
     *
     * Đóng thời gian hiệu lực giá cũ và tạo bản ghi giá mới.
     */
    private void savePriceHistory(
            PartnerMenuItem item,
            BigDecimal amount,
            String currency,
            String note
    ) {
        LocalDateTime now = LocalDateTime.now();

        priceRepository
                .findTopByMenuItem_IdAndEffectiveToIsNullOrderByEffectiveFromDesc(
                        item.getId()
                )
                .ifPresent(currentPrice -> {
                    currentPrice.setEffectiveTo(now);
                    priceRepository.save(currentPrice);
                });

        PartnerMenuItemPrice price =
                new PartnerMenuItemPrice();

        price.setMenuItem(item);
        price.setAmount(amount);
        price.setCurrency(currency);
        price.setEffectiveFrom(now);

        price.setPriceNote(
                note == null || note.isBlank()
                        ? null
                        : note.trim()
        );

        priceRepository.save(price);
    }

    private void validateCategoryRequest(
            MenuCategoryRequest request
    ) {
        if (request == null
                || request.getName() == null
                || request.getName().isBlank()) {

            throw new IllegalArgumentException(
                    "Tên danh mục không được để trống."
            );
        }
    }

    private void validateItemRequest(
            MenuItemRequest request
    ) {
        if (request == null
                || request.getName() == null
                || request.getName().isBlank()) {

            throw new IllegalArgumentException(
                    "Tên món/dịch vụ không được để trống."
            );
        }

        if (request.getPrice() == null
                || request.getPrice().compareTo(
                BigDecimal.ZERO
        ) < 0) {

            throw new IllegalArgumentException(
                    "Giá món/dịch vụ không hợp lệ."
            );
        }
    }

    private void applyCategory(
            PartnerMenuCategory category,
            MenuCategoryRequest request
    ) {
        category.setName(request.getName().trim());
        category.setDescription(request.getDescription());

        category.setSortOrder(
                request.getSortOrder() == null
                        ? 0
                        : request.getSortOrder()
        );

        category.setActive(
                request.getActive() == null
                        || request.getActive()
        );
    }

    private void applyItem(
            PartnerMenuItem item,
            MenuItemRequest request
    ) {
        item.setName(request.getName().trim());
        item.setDescription(request.getDescription());
        item.setPrice(request.getPrice());

        item.setCurrency(
                request.getCurrency() == null
                        || request.getCurrency().isBlank()
                        ? "VND"
                        : request.getCurrency()
                        .trim()
                        .toUpperCase(Locale.ROOT)
        );

        item.setAvailable(
                request.getAvailable() == null
                        || request.getAvailable()
        );

        item.setSortOrder(
                request.getSortOrder() == null
                        ? 0
                        : request.getSortOrder()
        );
    }
}