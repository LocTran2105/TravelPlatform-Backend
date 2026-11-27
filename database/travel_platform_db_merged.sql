/* ============================================================================
   TRAVEL PLATFORM DATABASE - CLEAN INSTALL
   Target: MySQL 8.0.16+ (InnoDB, utf8mb4)
   File: travel_platform_db_full.sql

   MERGED 2026-11-25: schema gốc (Partner/Admin/Place/AI) + phần mạng xã hội,
   lời mời, link chia sẻ, lịch sử thao tác (trip_activities), ảnh bìa (cover_images,
   trips.cover_image_url), users.username, posts.visibility/status.

   MERGED 2026-11-27: module báo cáo / kiểm duyệt nội dung (ROLE_MODERATOR,
   post_comments.status + moderated_*, place_reviews.moderated_*, reports.reason_code/
   resolution + mỗi người báo cáo 1 lần / nội dung, bảng moderation_actions).

   WARNING:
   - THIS SCRIPT DROPS THE EXISTING DATABASE travel_platform_db COMPLETELY.
   - Export/backup the old database before running this file.
   - This is a clean-install schema, not an in-place migration.

   CHANGE NOTES
   [UPDATE] Partner application/submission/audit columns aligned with current Java entities.
   [UPDATE] posts uses moderation_status + moderator metadata for admin moderation.
   [NEW]  Partner application/profile/business management, business hours,
          special hours, menu, price history, promotions, media, tourist notes,
          submissions and audit logs.
          Purpose: verified partner-managed source data with admin approval.
   [NEW]  partner_businesses is the shared business table for CAFE, RESTAURANT,
          HOTEL, HOMESTAY, ATTRACTION, TOUR, TRANSPORT and OTHER.
          Purpose: a hotel is now one business type, not a separate domain table.
   [UPDATE] rooms.hotel_id is replaced with rooms.business_id.
            Reason: rooms belong to any partner business of type HOTEL/HOMESTAY/
            RESORT; bookings still refer to rooms and preserve the booking flow.
   [UPDATE] users.role remains a single role string for compatibility with the
            current Spring Security/AuthResponse code. New accounts default to
            ROLE_TRAVELLER. Partner/Admin permissions must be granted server-side.
   [UPDATE] places can optionally map to a verified partner_businesses record.
            Reason: keep external discovery records separate from verified data.
   [UPDATE] Generic images/likes/reports/shares retain target_type + target_id
            because they refer to multiple entity tables. These polymorphic
            references cannot be protected by a normal single-table FK.
            Partner business media uses a dedicated FK-backed table instead.
   [DELETE] hotels table.
            Reason: superseded by partner_businesses.business_type = 'HOTEL'.
   [DELETE] hard-coded AI test user/trip IDs 9999 and fake place sample rows.
            Reason: test fixtures should not be inserted into a clean production
            schema; sample rating/pricing values could be mistaken for verified
            real-world data. AI tables themselves are retained.
   ============================================================================ */

DROP DATABASE IF EXISTS travel_platform_db;
CREATE DATABASE travel_platform_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE travel_platform_db;
/* ============================================================================
   1. USERS & AUTHENTICATION
   ============================================================================ */

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20) NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    avatar VARCHAR(1000) NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'ROLE_TRAVELLER'
        COMMENT 'ROLE_TRAVELLER, ROLE_PARTNER, ROLE_MODERATOR, ROLE_ADMIN',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE, LOCKED, DISABLED',
    reset_password_token VARCHAR(255) NULL,
    token_expiry_date DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email),
    UNIQUE KEY uq_users_phone (phone_number),
    UNIQUE KEY uq_users_reset_token (reset_password_token),
    KEY idx_users_role_status (role, status),
    CONSTRAINT chk_users_role CHECK (role IN ('ROLE_TRAVELLER', 'ROLE_PARTNER', 'ROLE_MODERATOR', 'ROLE_ADMIN')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
) ENGINE=InnoDB;

/* ============================================================================
   2. PARTNER ONBOARDING & VERIFIED BUSINESS DATA
   ============================================================================ */

-- [NEW] A partner profile represents a verified partner account.
-- A user may own multiple businesses through this profile.
CREATE TABLE partner_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    legal_name VARCHAR(255) NULL,
    tax_code VARCHAR(50) NULL,
    contact_person VARCHAR(150) NULL,
    contact_email VARCHAR(255) NULL,
    contact_phone VARCHAR(20) NULL,
    business_address VARCHAR(500) NULL,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, APPROVED, VERIFIED, REJECTED, SUSPENDED',
    verified_by BIGINT NULL,
    verified_at DATETIME NULL,
    rejection_reason TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_partner_profiles_user (user_id),
    KEY idx_partner_profiles_status (verification_status),
    KEY idx_partner_profiles_verified_by (verified_by),
    CONSTRAINT fk_partner_profiles_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_partner_profiles_verified_by
        FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_profiles_status
        CHECK (verification_status IN ('PENDING', 'APPROVED', 'VERIFIED', 'REJECTED', 'SUSPENDED'))
) ENGINE=InnoDB;

-- [NEW] Travellers submit an application before receiving ROLE_PARTNER.
CREATE TABLE partner_applications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    legal_name VARCHAR(255) NULL,
    tax_code VARCHAR(50) NULL,
    contact_person VARCHAR(150) NULL,
    contact_email VARCHAR(255) NULL,
    contact_phone VARCHAR(20) NULL,
    business_address VARCHAR(500) NULL,
    description TEXT NULL,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, APPROVED, REJECTED, CANCELLED',
    admin_note TEXT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_partner_applications_user (user_id, created_at),
    KEY idx_partner_applications_status (verification_status, created_at),
    KEY idx_partner_applications_reviewer (reviewed_by),
    CONSTRAINT fk_partner_applications_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_partner_applications_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_applications_status CHECK
        (verification_status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
) ENGINE=InnoDB;

-- [NEW] Shared business directory. HOTEL/HOMESTAY/RESORT are business types.
CREATE TABLE partner_businesses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    partner_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NULL,
    business_type VARCHAR(40) NOT NULL
        COMMENT 'CAFE, RESTAURANT, HOTEL, HOMESTAY, RESORT, ATTRACTION, TOUR, TRANSPORT, OTHER',
    short_description VARCHAR(500) NULL,
    description TEXT NULL,
    address VARCHAR(500) NOT NULL,
    ward VARCHAR(120) NULL,
    district VARCHAR(120) NULL,
    city VARCHAR(120) NOT NULL,
    province VARCHAR(120) NULL,
    country_code CHAR(2) NOT NULL DEFAULT 'VN',
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    phone_number VARCHAR(30) NULL,
    email VARCHAR(255) NULL,
    website_url VARCHAR(1000) NULL,
    facebook_url VARCHAR(1000) NULL,
    instagram_url VARCHAR(1000) NULL,
    price_level TINYINT NULL COMMENT '1..4; broad price band, not an exact item price',
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    verification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW'
        COMMENT 'DRAFT, PENDING_REVIEW, APPROVED, VERIFIED, REJECTED, SUSPENDED',
    publication_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT, PUBLISHED, HIDDEN',
    operating_status VARCHAR(30) NOT NULL DEFAULT 'OPEN'
        COMMENT 'OPEN, TEMPORARILY_CLOSED, CLOSED_PERMANENTLY, SEASONAL',
    verified_by BIGINT NULL,
    verified_at DATETIME NULL,
    rejection_reason TEXT NULL,
    source_note VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_partner_business_slug (slug),
    KEY idx_partner_business_partner (partner_id, deleted_at),
    KEY idx_partner_business_type_city (business_type, city),
    KEY idx_partner_business_status (verification_status, publication_status, operating_status),
    KEY idx_partner_business_geo (latitude, longitude),
    KEY idx_partner_business_name (name),
    KEY idx_partner_business_verified_by (verified_by),
    CONSTRAINT fk_partner_business_partner
        FOREIGN KEY (partner_id) REFERENCES partner_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_partner_business_verified_by
        FOREIGN KEY (verified_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_business_type CHECK
        (business_type IN ('CAFE', 'RESTAURANT', 'HOTEL', 'HOMESTAY', 'RESORT', 'ATTRACTION', 'TOUR', 'TRANSPORT', 'OTHER')),
    CONSTRAINT chk_partner_business_verification CHECK
        (verification_status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'VERIFIED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT chk_partner_business_publication CHECK
        (publication_status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT chk_partner_business_operating CHECK
        (operating_status IN ('OPEN', 'TEMPORARILY_CLOSED', 'CLOSED_PERMANENTLY', 'SEASONAL')),
    CONSTRAINT chk_partner_business_price_level CHECK
        (price_level IS NULL OR price_level BETWEEN 1 AND 4),
    CONSTRAINT chk_partner_business_latitude CHECK
        (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_partner_business_longitude CHECK
        (longitude IS NULL OR longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB;

-- [NEW] Recurring weekly opening hours. Multiple slots/day are supported.
CREATE TABLE partner_business_hours (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    weekday TINYINT NOT NULL COMMENT '1=Monday ... 7=Sunday',
    slot_number TINYINT NOT NULL DEFAULT 1,
    is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    open_time TIME NULL,
    close_time TIME NULL,
    closes_next_day BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_business_hours_slot (business_id, weekday, slot_number),
    CONSTRAINT fk_partner_business_hours_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE,
    CONSTRAINT chk_business_hours_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT chk_business_hours_slot CHECK (slot_number >= 1),
    CONSTRAINT chk_business_hours_values CHECK
        ((is_closed = TRUE AND open_time IS NULL AND close_time IS NULL)
         OR (is_closed = FALSE AND open_time IS NOT NULL AND close_time IS NOT NULL))
) ENGINE=InnoDB;

-- [NEW] Date-specific opening hours, holidays and temporary closures.
CREATE TABLE partner_business_special_hours (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    special_date DATE NOT NULL,
    is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    open_time TIME NULL,
    close_time TIME NULL,
    closes_next_day BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_business_special_date (business_id, special_date),
    KEY idx_business_special_date (special_date),
    CONSTRAINT fk_partner_business_special_hours_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE,
    CONSTRAINT chk_business_special_hours_values CHECK
        ((is_closed = TRUE AND open_time IS NULL AND close_time IS NULL)
         OR (is_closed = FALSE AND open_time IS NOT NULL AND close_time IS NOT NULL))
) ENGINE=InnoDB;

-- [NEW] Menu/service categories.
CREATE TABLE partner_menu_categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(500) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_menu_categories_business (business_id, is_active, sort_order),
    CONSTRAINT fk_partner_menu_categories_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- [NEW] Menu items or paid services. current_price is the currently displayed price.
CREATE TABLE partner_menu_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    category_id BIGINT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    image_url VARCHAR(1000) NULL,
    current_price DECIMAL(15,2) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    unit_label VARCHAR(80) NULL COMMENT 'ly, phần, người, giờ, đêm...',
    availability_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
        COMMENT 'AVAILABLE, UNAVAILABLE, SEASONAL',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_menu_items_business (business_id, is_active, sort_order),
    KEY idx_menu_items_category (category_id),
    CONSTRAINT fk_partner_menu_items_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE,
    CONSTRAINT fk_partner_menu_items_category
        FOREIGN KEY (category_id) REFERENCES partner_menu_categories(id) ON DELETE SET NULL,
    CONSTRAINT chk_menu_item_price CHECK (current_price IS NULL OR current_price >= 0),
    CONSTRAINT chk_menu_item_availability CHECK
        (availability_status IN ('AVAILABLE', 'UNAVAILABLE', 'SEASONAL'))
) ENGINE=InnoDB;

-- [NEW] Append-only price history with explicit effective periods.
CREATE TABLE partner_menu_item_prices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    menu_item_id BIGINT NOT NULL,
    price DECIMAL(15,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    effective_from DATETIME NOT NULL,
    effective_to DATETIME NULL,
    source VARCHAR(30) NOT NULL DEFAULT 'PARTNER'
        COMMENT 'PARTNER, ADMIN, IMPORT',
    recorded_by BIGINT NULL,
    note VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_menu_price_item_period (menu_item_id, effective_from, effective_to),
    KEY idx_menu_price_recorded_by (recorded_by),
    CONSTRAINT fk_partner_menu_prices_item
        FOREIGN KEY (menu_item_id) REFERENCES partner_menu_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_partner_menu_prices_user
        FOREIGN KEY (recorded_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_menu_price_amount CHECK (price >= 0),
    CONSTRAINT chk_partner_menu_price_period CHECK
        (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB;

-- [NEW] Promotions and sales campaigns.
CREATE TABLE partner_promotions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    promotion_type VARCHAR(30) NOT NULL
        COMMENT 'PERCENT, FIXED_AMOUNT, SPECIAL_PRICE, GIFT, OTHER',
    discount_value DECIMAL(15,2) NULL,
    promotion_code VARCHAR(80) NULL,
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    terms TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT, PENDING_REVIEW, APPROVED, REJECTED, EXPIRED, CANCELLED',
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_promotions_business_period (business_id, status, starts_at, ends_at),
    KEY idx_promotions_approver (approved_by),
    CONSTRAINT fk_partner_promotions_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE,
    CONSTRAINT fk_partner_promotions_approver
        FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_promotion_type CHECK
        (promotion_type IN ('PERCENT', 'FIXED_AMOUNT', 'SPECIAL_PRICE', 'GIFT', 'OTHER')),
    CONSTRAINT chk_partner_promotion_status CHECK
        (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_partner_promotion_period CHECK (ends_at > starts_at),
    CONSTRAINT chk_partner_promotion_value CHECK (discount_value IS NULL OR discount_value >= 0)
) ENGINE=InnoDB;

-- [NEW] Partner-owned business photos/videos with real FK integrity.
CREATE TABLE partner_business_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    media_type VARCHAR(20) NOT NULL DEFAULT 'IMAGE'
        COMMENT 'IMAGE, VIDEO, DOCUMENT',
    media_url VARCHAR(1000) NOT NULL,
    thumbnail_url VARCHAR(1000) NULL,
    caption VARCHAR(500) NULL,
    alt_text VARCHAR(500) NULL,
    is_cover BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    moderation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, APPROVED, REJECTED',
    uploaded_by BIGINT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_business_media_business (business_id, moderation_status, sort_order),
    KEY idx_business_media_uploaded_by (uploaded_by),
    KEY idx_business_media_reviewed_by (reviewed_by),
    CONSTRAINT fk_partner_business_media_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE,
    CONSTRAINT fk_partner_business_media_uploader
        FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_partner_business_media_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_business_media_type CHECK
        (media_type IN ('IMAGE', 'VIDEO', 'DOCUMENT')),
    CONSTRAINT chk_partner_business_media_moderation CHECK
        (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB;

-- [NEW] Tourist-facing practical notes (parking, dress code, cash, access, etc.).
CREATE TABLE partner_business_notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    note_type VARCHAR(40) NOT NULL DEFAULT 'GENERAL'
        COMMENT 'GENERAL, PARKING, ACCESS, PAYMENT, SAFETY, BOOKING, POLICY, OTHER',
    language_code VARCHAR(10) NOT NULL DEFAULT 'vi',
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from DATETIME NULL,
    valid_to DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_business_notes_visible (business_id, is_published, valid_from, valid_to),
    CONSTRAINT fk_partner_business_notes_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE CASCADE,
    CONSTRAINT chk_partner_business_note_period CHECK
        (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB;

-- [NEW] Review queue for partner-created/updated/deleted data.
-- target_type/target_id are polymorphic; payload stores the proposed snapshot.
CREATE TABLE partner_submissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    partner_id BIGINT NULL,
    business_id BIGINT NULL,
    submitted_by BIGINT NOT NULL,
    submission_type VARCHAR(40) NULL
        COMMENT 'BUSINESS_PROFILE, MENU, PRICE, PROMOTION, MEDIA, NOTE, ROOM, OTHER',
    submission_data JSON NULL,
    target_type VARCHAR(40) NULL
        COMMENT 'BUSINESS, HOURS, SPECIAL_HOURS, MENU_CATEGORY, MENU_ITEM, PRICE, PROMOTION, MEDIA, NOTE, ROOM',
    target_id BIGINT NULL,
    action_type VARCHAR(20) NULL COMMENT 'CREATE, UPDATE, DELETE',
    requested_payload JSON NULL,
    previous_snapshot JSON NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, APPROVED, REJECTED, CANCELLED',
    review_note TEXT NULL,
    reviewed_by BIGINT NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_partner_submissions_queue (status, submitted_at),
    KEY idx_partner_submissions_partner (partner_id, submitted_at),
    KEY idx_partner_submissions_business (business_id, submitted_at),
    KEY idx_partner_submissions_reviewer (reviewed_by),
    CONSTRAINT fk_partner_submissions_partner
        FOREIGN KEY (partner_id) REFERENCES partner_profiles(id) ON DELETE SET NULL,
    CONSTRAINT fk_partner_submissions_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE SET NULL,
    CONSTRAINT fk_partner_submissions_submitter
        FOREIGN KEY (submitted_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_partner_submissions_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_partner_submission_action
        CHECK (action_type IS NULL OR action_type IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT chk_partner_submission_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
) ENGINE=InnoDB;

-- [NEW] Audit trail. Entity ID is polymorphic; actor/business IDs are FK-backed.
CREATE TABLE partner_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    partner_id BIGINT NULL,
    business_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    submission_id BIGINT NULL,
    action VARCHAR(30) NOT NULL
        COMMENT 'CREATE, UPDATE, DELETE, APPROVE, REJECT, SUSPEND, PUBLISH',
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT NULL,
    before_data JSON NULL,
    after_data JSON NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_partner_audit_partner_time (partner_id, created_at),
    KEY idx_partner_audit_business_time (business_id, created_at),
    KEY idx_partner_audit_entity (entity_type, entity_id),
    KEY idx_partner_audit_actor_time (actor_user_id, created_at),
    KEY idx_partner_audit_submission (submission_id),
    CONSTRAINT fk_partner_audit_partner
        FOREIGN KEY (partner_id) REFERENCES partner_profiles(id) ON DELETE SET NULL,
    CONSTRAINT fk_partner_audit_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE SET NULL,
    CONSTRAINT fk_partner_audit_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_partner_audit_submission
        FOREIGN KEY (submission_id) REFERENCES partner_submissions(id) ON DELETE SET NULL
) ENGINE=InnoDB;

/* ============================================================================
   3. TRIPS & ITINERARY
   ============================================================================ */

CREATE TABLE trips (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    member_count INT NOT NULL DEFAULT 1,
    total_budget DECIMAL(15,2) NULL DEFAULT 0.00,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE'
        COMMENT 'PUBLIC, FRIENDS, PRIVATE',
    cloned_from_trip_id BIGINT NULL,
    public_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT, PENDING, APPROVED, REJECTED',
    average_rating DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    total_reviews INT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_trips_user_created (user_id, created_at),
    KEY idx_trips_visibility_status (visibility, public_status),
    KEY idx_trips_is_public (is_public),
    KEY idx_trips_cloned_from (cloned_from_trip_id),
    CONSTRAINT fk_trips_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_trips_cloned_from FOREIGN KEY (cloned_from_trip_id) REFERENCES trips(id) ON DELETE SET NULL,
    CONSTRAINT chk_trips_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date),
    CONSTRAINT chk_trips_budget CHECK (total_budget IS NULL OR total_budget >= 0),
    CONSTRAINT chk_trips_visibility CHECK (visibility IN ('PUBLIC', 'FRIENDS', 'PRIVATE')),
    CONSTRAINT chk_trips_public_status CHECK (public_status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_trips_average_rating CHECK (average_rating BETWEEN 0 AND 5)
) ENGINE=InnoDB;

CREATE TABLE trip_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
        COMMENT 'OWNER, EDITOR, VIEWER, MEMBER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED'
        COMMENT 'PENDING, ACCEPTED, DECLINED, REMOVED',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_trip_member (trip_id, user_id),
    KEY idx_trip_members_user (user_id),
    CONSTRAINT fk_trip_members_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_trip_member_role CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER', 'MEMBER')),
    CONSTRAINT chk_trip_member_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REMOVED'))
) ENGINE=InnoDB;

CREATE TABLE trip_days (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    day_number INT NOT NULL,
    day_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_trip_day_number (trip_id, day_number),
    KEY idx_trip_days_date (day_date),
    CONSTRAINT fk_trip_days_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT chk_trip_day_number CHECK (day_number >= 1)
) ENGINE=InnoDB;

/* ============================================================================
   4. PLACES DIRECTORY & TRIP DESTINATIONS
   ============================================================================ */

-- [UPDATE] place discovery data remains separate from partner-owned verified data.
-- partner_business_id optionally links an external POI to the verified record.
CREATE TABLE places (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(30) NOT NULL DEFAULT 'OSM',
    provider_place_id VARCHAR(255) NULL,
    partner_business_id BIGINT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500) NULL,
    destination VARCHAR(100) NOT NULL,
    place_type VARCHAR(40) NOT NULL DEFAULT 'ATTRACTION',
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    rating DECIMAL(3,2) NULL,
    user_ratings_total INT UNSIGNED NULL,
    categories JSON NULL,
    estimated_cost_min DECIMAL(15,2) NULL,
    estimated_cost_max DECIMAL(15,2) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    price_updated_at DATETIME NULL,
    fetched_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_places_provider_id (provider, provider_place_id),
    KEY idx_places_partner_business (partner_business_id),
    KEY idx_places_destination_type (destination, place_type),
    KEY idx_places_rating (rating),
    KEY idx_places_geo (latitude, longitude),
    CONSTRAINT fk_places_partner_business
        FOREIGN KEY (partner_business_id) REFERENCES partner_businesses(id) ON DELETE SET NULL,
    CONSTRAINT chk_places_rating CHECK (rating IS NULL OR rating BETWEEN 0 AND 5),
    CONSTRAINT chk_places_cost_range CHECK
        ((estimated_cost_min IS NULL OR estimated_cost_min >= 0)
         AND (estimated_cost_max IS NULL OR estimated_cost_max >= 0)
         AND (estimated_cost_min IS NULL OR estimated_cost_max IS NULL OR estimated_cost_max >= estimated_cost_min)),
    CONSTRAINT chk_places_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_places_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB;

CREATE TABLE trip_destinations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_day_id BIGINT NOT NULL,
    place_id BIGINT NULL,
    place_name VARCHAR(255) NOT NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    note TEXT NULL,
    estimated_cost DECIMAL(15,2) NULL,
    cost VARCHAR(100) NULL COMMENT 'Legacy display text, e.g. 10.000 đ',
    order_index INT NULL,
    is_visited BOOLEAN NOT NULL DEFAULT FALSE,
    time_range VARCHAR(100) NULL COMMENT 'Legacy display text, e.g. 14:30 - 17:00',
    item_type VARCHAR(30) NOT NULL DEFAULT 'ATTRACTION'
        COMMENT 'ATTRACTION, ACCOMMODATION, RESTAURANT, TRANSPORT, ACTIVITY, OTHER',
    start_time TIME NULL,
    end_time TIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_trip_destinations_day_order (trip_day_id, order_index),
    KEY idx_trip_destinations_place (place_id),
    CONSTRAINT fk_trip_destinations_day
        FOREIGN KEY (trip_day_id) REFERENCES trip_days(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_destinations_place
        FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE SET NULL,
    CONSTRAINT chk_trip_destination_cost CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
    CONSTRAINT chk_trip_destination_time CHECK
        (start_time IS NULL OR end_time IS NULL OR end_time >= start_time)
) ENGINE=InnoDB;

/* ============================================================================
   5. EXPENSES & SPLITS
   ============================================================================ */

CREATE TABLE expenses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    trip_destination_id BIGINT NULL,
    payer_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(30) NOT NULL DEFAULT 'OTHER'
        COMMENT 'FLIGHTS, LODGING, CAR_RENTAL, TRANSIT, FOOD, DRINKS, SIGHTSEEING, ACTIVITIES, SHOPPING, GAS, GROCERIES, OTHER',
    description TEXT NULL,
    amount DECIMAL(15,2) NOT NULL,
    expense_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_expenses_trip_date (trip_id, expense_date),
    KEY idx_expenses_payer (payer_id),
    KEY idx_expenses_destination (trip_destination_id),
    CONSTRAINT fk_expenses_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_expenses_payer FOREIGN KEY (payer_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_expenses_destination
        FOREIGN KEY (trip_destination_id) REFERENCES trip_destinations(id) ON DELETE SET NULL,
    CONSTRAINT chk_expenses_amount CHECK (amount >= 0)
) ENGINE=InnoDB;

CREATE TABLE expense_splits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    expense_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount_owed DECIMAL(15,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_split_user (expense_id, user_id),
    KEY idx_expense_splits_user (user_id),
    CONSTRAINT fk_expense_splits_expense FOREIGN KEY (expense_id) REFERENCES expenses(id) ON DELETE CASCADE,
    CONSTRAINT fk_expense_splits_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_expense_split_amount CHECK (amount_owed >= 0)
) ENGINE=InnoDB;

/* ============================================================================
   6. HOTEL / HOMESTAY ROOMS & BOOKINGS
   ============================================================================ */

-- [UPDATE] hotels table is removed. Its generalized replacement is
-- partner_businesses.business_type='HOTEL' (or HOMESTAY/RESORT).
-- [UPDATE] rooms.hotel_id becomes rooms.business_id and references that table.
CREATE TABLE rooms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_id BIGINT NOT NULL,
    room_name VARCHAR(150) NOT NULL,
    description TEXT NULL,
    price_per_night DECIMAL(15,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    capacity INT NOT NULL DEFAULT 2,
    total_units INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE, INACTIVE, MAINTENANCE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rooms_business (business_id, status),
    CONSTRAINT fk_rooms_business
        FOREIGN KEY (business_id) REFERENCES partner_businesses(id) ON DELETE RESTRICT,
    CONSTRAINT chk_rooms_price CHECK (price_per_night >= 0),
    CONSTRAINT chk_rooms_capacity CHECK (capacity >= 1),
    CONSTRAINT chk_rooms_total_units CHECK (total_units >= 1),
    CONSTRAINT chk_rooms_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE'))
) ENGINE=InnoDB;

CREATE TABLE bookings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL COMMENT 'Người đặt phòng',
    check_in DATE NOT NULL,
    check_out DATE NOT NULL,
    guest_count INT NOT NULL DEFAULT 1,
    total_price DECIMAL(15,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, CONFIRMED, CANCELLED, COMPLETED, REJECTED',
    customer_note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_bookings_room_dates (room_id, check_in, check_out),
    KEY idx_bookings_user_created (user_id, created_at),
    KEY idx_bookings_status (status),
    CONSTRAINT fk_bookings_room FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_bookings_dates CHECK (check_out > check_in),
    CONSTRAINT chk_bookings_guests CHECK (guest_count >= 1),
    CONSTRAINT chk_bookings_total_price CHECK (total_price >= 0),
    CONSTRAINT chk_bookings_status CHECK
        (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'REJECTED'))
) ENGINE=InnoDB;

/* ============================================================================
   7. USER-DEFINED PLACES & ALERTS
   ============================================================================ */

CREATE TABLE custom_places (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    images TEXT NULL COMMENT 'JSON array of image URLs; TEXT is retained for compatibility with existing entity mapping',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_custom_places_user (user_id),
    KEY idx_custom_places_geo (latitude, longitude),
    CONSTRAINT fk_custom_places_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_custom_places_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_custom_places_longitude CHECK (longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB;

CREATE TABLE alerts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    alert_type VARCHAR(100) NULL COMMENT 'LANDSLIDE, PRICE_SCAM, POLICE_CHECKPOINT, WEATHER, OTHER',
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, APPROVED, REJECTED, EXPIRED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_alerts_user (user_id),
    KEY idx_alerts_status_created (status, created_at),
    KEY idx_alerts_geo (latitude, longitude),
    CONSTRAINT fk_alerts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_alerts_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_alerts_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_alerts_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
) ENGINE=InnoDB;

/* ============================================================================
   8. REVIEWS, COMMENTS, REPORTS & SOCIAL FEED
   ============================================================================ */

CREATE TABLE trip_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    comment TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_trip_review_user (trip_id, user_id),
    KEY idx_trip_reviews_user (user_id),
    CONSTRAINT fk_trip_reviews_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_reviews_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_trip_review_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE trip_comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_trip_comments_trip_time (trip_id, created_at),
    KEY idx_trip_comments_user (user_id),
    KEY idx_trip_comments_parent (parent_id),
    CONSTRAINT fk_trip_comments_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_comments_parent FOREIGN KEY (parent_id) REFERENCES trip_comments(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE posts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    caption TEXT NULL,
    location_name VARCHAR(255) NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    rating TINYINT NULL,
    moderation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, APPROVED, REJECTED, HIDDEN',
    moderated_by BIGINT NULL,
    moderation_note TEXT NULL,
    moderated_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_posts_user_time (user_id, created_at),
    KEY idx_posts_location (latitude, longitude),
    KEY idx_posts_moderation_created (moderation_status, created_at),
    KEY idx_posts_moderated_by (moderated_by),
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_posts_moderated_by
        FOREIGN KEY (moderated_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_posts_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    CONSTRAINT chk_posts_moderation_status
        CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN')),
    CONSTRAINT chk_posts_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_posts_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB;

CREATE TABLE post_comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        COMMENT 'PUBLISHED, HIDDEN',
    moderated_by BIGINT NULL,
    moderation_note VARCHAR(500) NULL,
    moderated_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_post_comments_post_time (post_id, created_at),
    KEY idx_post_comments_user (user_id),
    KEY idx_post_comments_parent (parent_id),
    KEY idx_post_comments_post_status (post_id, status),
    CONSTRAINT fk_post_comments_post FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_post_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_post_comments_parent FOREIGN KEY (parent_id) REFERENCES post_comments(id) ON DELETE CASCADE,
    CONSTRAINT fk_post_comments_moderated_by
        FOREIGN KEY (moderated_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_post_comments_status CHECK (status IN ('PUBLISHED', 'HIDDEN'))
) ENGINE=InnoDB;

CREATE TABLE place_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    place_id BIGINT NULL,
    location_name VARCHAR(255) NOT NULL,
    rating TINYINT NOT NULL,
    content TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        COMMENT 'PENDING, PUBLISHED, HIDDEN, REJECTED',
    moderated_by BIGINT NULL,
    moderation_note VARCHAR(500) NULL,
    moderated_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_place_reviews_location (location_name),
    KEY idx_place_reviews_place_time (place_id, created_at),
    KEY idx_place_reviews_user (user_id),
    CONSTRAINT fk_place_reviews_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_place_reviews_place FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE SET NULL,
    CONSTRAINT fk_place_reviews_moderated_by
        FOREIGN KEY (moderated_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_place_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_place_review_status CHECK (status IN ('PENDING', 'PUBLISHED', 'HIDDEN', 'REJECTED'))
) ENGINE=InnoDB;

CREATE TABLE place_review_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    media_url VARCHAR(1000) NOT NULL,
    media_type VARCHAR(10) NOT NULL COMMENT 'IMAGE, VIDEO',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_place_review_media_review (review_id),
    CONSTRAINT fk_place_review_media_review FOREIGN KEY (review_id) REFERENCES place_reviews(id) ON DELETE CASCADE,
    CONSTRAINT chk_place_review_media_type CHECK (media_type IN ('IMAGE', 'VIDEO'))
) ENGINE=InnoDB;

CREATE TABLE reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT NOT NULL,
    target_type VARCHAR(50) NOT NULL
        COMMENT 'POST, POST_COMMENT, PLACE_REVIEW (cũ: TRIP, COMMENT, ALERT, CUSTOM_PLACE, BUSINESS)',
    target_id BIGINT NOT NULL,
    reason_code VARCHAR(30) NULL
        COMMENT 'SPAM, HARASSMENT, SENSITIVE, MISINFORMATION, OTHER',
    reason TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, RESOLVED, REJECTED',
    resolution VARCHAR(20) NULL
        COMMENT 'HIDDEN, DISMISSED',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reports_reporter_target (reporter_id, target_type, target_id),
    KEY idx_reports_status_time (status, created_at),
    KEY idx_reports_target (target_type, target_id),
    KEY idx_reports_reporter (reporter_id),
    KEY idx_reports_reviewer (reviewed_by),
    KEY idx_reports_target_status (target_type, target_id, status),
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_reports_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_reports_status CHECK (status IN ('PENDING', 'RESOLVED', 'REJECTED')),
    CONSTRAINT chk_reports_reason_code
        CHECK (reason_code IS NULL OR reason_code IN ('SPAM', 'HARASSMENT', 'SENSITIVE', 'MISINFORMATION', 'OTHER')),
    CONSTRAINT chk_reports_resolution
        CHECK (resolution IS NULL OR resolution IN ('HIDDEN', 'DISMISSED'))
) ENGINE=InnoDB;

-- Nhật ký kiểm duyệt: ẩn / khôi phục nội dung, bỏ qua báo cáo.
CREATE TABLE moderation_actions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_id BIGINT NULL,
    action VARCHAR(20) NOT NULL COMMENT 'HIDE, RESTORE, DISMISS',
    target_type VARCHAR(50) NOT NULL COMMENT 'POST, POST_COMMENT, PLACE_REVIEW',
    target_id BIGINT NOT NULL,
    target_author_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    report_count INT NOT NULL DEFAULT 0 COMMENT 'Số báo cáo được xử lý cùng thao tác này',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_mod_actions_time (created_at),
    KEY idx_mod_actions_target (target_type, target_id, created_at),
    KEY idx_mod_actions_actor (actor_id, created_at),
    KEY idx_mod_actions_author (target_author_id),
    CONSTRAINT fk_mod_actions_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_mod_actions_author FOREIGN KEY (target_author_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_mod_actions_action CHECK (action IN ('HIDE', 'RESTORE', 'DISMISS')),
    CONSTRAINT chk_mod_actions_target_type CHECK (target_type IN ('POST', 'POST_COMMENT', 'PLACE_REVIEW'))
) ENGINE=InnoDB;

-- Generic legacy media for trip/post/custom-place/alert objects.
-- Partner assets should use partner_business_media for FK integrity.
CREATE TABLE images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(50) NOT NULL
        COMMENT 'TRIP, TRIP_DESTINATION, CUSTOM_PLACE, ALERT, POST',
    entity_id BIGINT NOT NULL,
    image_url VARCHAR(1000) NOT NULL,
    media_type VARCHAR(20) NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE, VIDEO',
    caption VARCHAR(500) NULL,
    is_cover BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_by BIGINT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_images_entity (entity_type, entity_id),
    KEY idx_images_uploader (uploaded_by),
    CONSTRAINT fk_images_uploader FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_images_media_type CHECK (media_type IN ('IMAGE', 'VIDEO'))
) ENGINE=InnoDB;

CREATE TABLE likes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    target_type VARCHAR(50) NOT NULL COMMENT 'TRIP, TRIP_COMMENT, REVIEW, CUSTOM_PLACE, POST, BUSINESS',
    target_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_likes_user_target (user_id, target_type, target_id),
    KEY idx_likes_target (target_type, target_id),
    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE follows (
    id BIGINT NOT NULL AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    followed_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_follows_pair (follower_id, followed_id),
    KEY idx_follows_followed (followed_id),
    CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_follows_followed FOREIGN KEY (followed_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_follows_not_self CHECK (follower_id <> followed_id)
) ENGINE=InnoDB;

CREATE TABLE shares (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    target_type VARCHAR(50) NOT NULL COMMENT 'TRIP, BUSINESS, CUSTOM_PLACE, POST',
    target_id BIGINT NOT NULL,
    platform VARCHAR(50) NULL COMMENT 'FACEBOOK, ZALO, COPY_LINK, OTHER',
    shared_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shares_user_time (user_id, shared_at),
    KEY idx_shares_target (target_type, target_id),
    CONSTRAINT fk_shares_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

/* ============================================================================
   9. PREFERENCES & RECOMMENDATION EVENTS
   ============================================================================ */

CREATE TABLE user_preferences (
    user_id BIGINT NOT NULL,
    preference_tags JSON NULL,
    preferred_accommodation VARCHAR(30) NULL,
    preferred_transport VARCHAR(30) NULL,
    travel_style VARCHAR(30) NOT NULL DEFAULT 'BALANCED',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE trip_preferences (
    trip_id BIGINT NOT NULL,
    preference_tags JSON NULL,
    preferred_accommodation VARCHAR(30) NULL COMMENT 'HOTEL, HOMESTAY, RESORT',
    preferred_transport VARCHAR(30) NULL COMMENT 'MOTORBIKE, CAR, TAXI, BUS',
    intensity VARCHAR(30) NOT NULL DEFAULT 'BALANCED' COMMENT 'RELAX, BALANCED, PACKED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (trip_id),
    CONSTRAINT fk_trip_preferences_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE recommendation_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trip_id BIGINT NULL,
    place_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL
        COMMENT 'IMPRESSION, VIEW, SAVE, ADD_TO_TRIP, REMOVE_FROM_TRIP, DISMISS, BOOKING',
    recommendation_score DECIMAL(7,4) NULL,
    position INT NULL,
    context JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_recommendation_user_time (user_id, created_at),
    KEY idx_recommendation_trip (trip_id),
    KEY idx_recommendation_place_event (place_id, event_type),
    CONSTRAINT fk_recommendation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_recommendation_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE SET NULL,
    CONSTRAINT fk_recommendation_place FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE place_price_quotes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    place_id BIGINT NOT NULL,
    quote_type VARCHAR(30) NOT NULL COMMENT 'HOTEL_ROOM, ENTRY_TICKET, MEAL, TRANSPORT',
    price_type VARCHAR(20) NOT NULL COMMENT 'LIVE_QUOTE, ESTIMATE, REFERENCE',
    provider VARCHAR(100) NOT NULL,
    provider_offer_id VARCHAR(255) NULL,
    amount DECIMAL(15,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    price_basis VARCHAR(30) NULL COMMENT 'PER_PERSON, PER_NIGHT, PER_VEHICLE, WHOLE_STAY',
    check_in DATE NULL,
    check_out DATE NULL,
    guest_count INT NULL,
    availability_status VARCHAR(30) NULL,
    source_url VARCHAR(1000) NULL,
    checked_at DATETIME NOT NULL,
    expires_at DATETIME NULL,
    details JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_price_quote_place_type_time (place_id, quote_type, checked_at),
    KEY idx_price_quote_expiry (expires_at),
    CONSTRAINT fk_price_quote_place FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
    CONSTRAINT chk_price_quote_amount CHECK (amount >= 0),
    CONSTRAINT chk_price_quote_guests CHECK (guest_count IS NULL OR guest_count >= 1),
    CONSTRAINT chk_price_quote_dates CHECK
        (check_out IS NULL OR check_in IS NULL OR check_out > check_in)
) ENGINE=InnoDB;

/* ============================================================================
   10. AI TABLES - RETAINED, NOT ACTIVELY EXPANDED IN THIS PHASE
   ============================================================================ */

CREATE TABLE ai_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, CLOSED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_conversation_trip_user (trip_id, user_id),
    CONSTRAINT fk_ai_conversation_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_conversation_status CHECK (status IN ('ACTIVE', 'CLOSED'))
) ENGINE=InnoDB;

CREATE TABLE ai_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sender_type VARCHAR(20) NOT NULL COMMENT 'USER, AI, SYSTEM, TOOL',
    content MEDIUMTEXT NOT NULL,
    action_data JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_messages_conversation_time (conversation_id, created_at),
    CONSTRAINT fk_ai_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_message_sender CHECK (sender_type IN ('USER', 'AI', 'SYSTEM', 'TOOL'))
) ENGINE=InnoDB;

CREATE TABLE ai_usage_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NULL,
    provider VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    key_slot VARCHAR(30) NULL COMMENT 'Identifier only; never store the actual API key',
    input_tokens INT UNSIGNED NOT NULL DEFAULT 0,
    output_tokens INT UNSIGNED NOT NULL DEFAULT 0,
    http_status SMALLINT UNSIGNED NULL,
    latency_ms INT UNSIGNED NULL,
    request_status VARCHAR(20) NULL COMMENT 'SUCCESS, FAILED, RATE_LIMITED',
    error_code VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_usage_created (created_at),
    KEY idx_ai_usage_status (request_status),
    CONSTRAINT fk_ai_usage_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id) ON DELETE SET NULL,
    CONSTRAINT chk_ai_usage_status CHECK
        (request_status IS NULL OR request_status IN ('SUCCESS', 'FAILED', 'RATE_LIMITED'))
) ENGINE=InnoDB;

/* ============================================================================
   11. OPTIONAL ADMIN BOOTSTRAP (DO NOT USE A PLAINTEXT PASSWORD)
   ============================================================================

   No default admin or partner account is inserted here intentionally.
   Create a BCrypt hash in the Spring application, then run for example:

   Sau khi chạy hết file này, bảng users có thêm cột username (NOT NULL, 3-10 ký tự
   a-z 0-9 . _ -), nên câu INSERT phải có username:

   INSERT INTO users (email, username, password_hash, full_name, role, status)
   VALUES ('admin@your-domain.vn', 'admin', '<BCrypt-hash-generated-by-your-app>',
           'System Administrator', 'ROLE_ADMIN', 'ACTIVE');

   Public registration must ALWAYS assign ROLE_TRAVELLER in AuthService.
   Partner applications are submitted separately; only after admin approval:
   1) create/update partner_profiles,
   2) change users.role to ROLE_PARTNER,
   3) write partner_audit_logs.

   IMPORTANT FOR EXISTING SPRING ENTITIES:
   - Hotel.java / HotelRepository / HotelService / HotelController must be
     migrated to PartnerBusiness (or compatibility DTO/service layer).
   - Room.java: map business_id instead of hotel_id and join to PartnerBusiness.
   - Booking.java can continue referencing Room through room_id.
   - Existing Place.java should add nullable partnerBusiness mapping if needed.
   - users.role remains VARCHAR, so the existing getRole()/AuthResponse flow
     can continue to work without introducing a new user_roles join table.
   ============================================================================ */

-- Them cho mxh
ALTER TABLE users
    ADD COLUMN username VARCHAR(10) NOT NULL
        COMMENT 'Unique public handle; 3-10 lowercase letters, digits, dot, underscore or hyphen'
        AFTER email,
    ADD UNIQUE KEY uq_users_username (username),
    ADD CONSTRAINT chk_users_username
        CHECK (
            REGEXP_LIKE(
                username,
                '^[a-z0-9][a-z0-9._-]{1,8}[a-z0-9]$',
                'c'
            )
        );

/* ===== 2. FOLLOWS: indexes for paginated Followers / Following lists ===== */
ALTER TABLE follows
    ADD KEY idx_follows_follower_time (follower_id, created_at, id),
    ADD KEY idx_follows_followed_time (followed_id, created_at, id);

/* ===== 3. POSTS: visibility / privacy ===== */
ALTER TABLE posts
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC'
        COMMENT 'PUBLIC, FOLLOWERS, FRIENDS, PRIVATE'
        AFTER rating,
    ADD CONSTRAINT chk_posts_visibility
        CHECK (visibility IN ('PUBLIC', 'FOLLOWERS', 'FRIENDS', 'PRIVATE')),
    ADD KEY idx_posts_user_visibility_time (user_id, visibility, created_at),
    ADD KEY idx_posts_feed (moderation_status, visibility, created_at);

ALTER TABLE posts
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        COMMENT 'PUBLISHED, DELETED'
        AFTER visibility,
    ADD CONSTRAINT chk_posts_status
        CHECK (status IN ('PUBLISHED', 'DELETED'));
        
/* ===== 4. TRIPS: align visibility values with the social relationship model ===== */
ALTER TABLE trips
    MODIFY COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE'
        COMMENT 'PUBLIC, FOLLOWERS, FRIENDS, PRIVATE',
    DROP CHECK chk_trips_visibility,
    ADD CONSTRAINT chk_trips_visibility
        CHECK (visibility IN ('PUBLIC', 'FOLLOWERS', 'FRIENDS', 'PRIVATE'));


/* ============================================================================
   TRIP INVITATIONS - lời mời tham gia chuyến đi qua email
*/

CREATE TABLE trip_invitations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    email VARCHAR(255) NOT NULL COMMENT 'Email được mời, luôn lưu chữ thường',
    role VARCHAR(20) NOT NULL DEFAULT 'EDITOR'
        COMMENT 'Quyền khi tham gia: EDITOR (được sửa), VIEWER (chỉ xem)',
    token_hash CHAR(64) NOT NULL
        COMMENT 'SHA-256 của token trong link. Token gốc chỉ nằm trong email, không lưu',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, ACCEPTED, DECLINED, REVOKED',
    invited_by BIGINT NOT NULL,
    accepted_by BIGINT NULL,
    expires_at DATETIME NOT NULL,
    responded_at DATETIME NULL,
    last_sent_at DATETIME NOT NULL,
    send_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- Cột sinh tự động: = 1 khi PENDING, NULL khi trạng thái khác.
    -- Cùng UNIQUE bên dưới, nó đảm bảo mỗi (chuyến đi, email) chỉ có 1 lời mời đang chờ,
    -- kể cả khi hai request chạy cùng lúc. NULL không bị tính trùng.
    pending_flag TINYINT AS (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_trip_invitation_token (token_hash),
    UNIQUE KEY uq_trip_invitation_pending (trip_id, email, pending_flag),
    KEY idx_trip_invitations_trip_status (trip_id, status),
    KEY idx_trip_invitations_email_status (email, status),
    KEY idx_trip_invitations_inviter_sent (invited_by, last_sent_at),
    CONSTRAINT fk_trip_invitations_trip
        FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_invitations_inviter
        FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_invitations_acceptor
        FOREIGN KEY (accepted_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_trip_invitation_role CHECK (role IN ('EDITOR', 'VIEWER')),
    CONSTRAINT chk_trip_invitation_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS trip_share_links (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    trip_id    BIGINT      NOT NULL,
    role       VARCHAR(10) NOT NULL COMMENT 'EDITOR, VIEWER',
    token      VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_trip_share_token (token),
    UNIQUE KEY uq_trip_share_trip_role (trip_id, role),
    CONSTRAINT fk_trip_share_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT chk_trip_share_role CHECK (role IN ('EDITOR', 'VIEWER'))
) ENGINE=InnoDB;


CREATE TABLE IF NOT EXISTS trip_activities (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    trip_id     BIGINT       NOT NULL,
    actor_id    BIGINT       NULL,
    action      VARCHAR(40)  NOT NULL,
    target_type VARCHAR(20)  NULL,
    target_id   BIGINT       NULL,
    label       VARCHAR(255) NULL,
    detail      VARCHAR(500) NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trip_activity_trip_id (trip_id, id),
    CONSTRAINT fk_trip_activity_trip  FOREIGN KEY (trip_id)  REFERENCES trips(id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_activity_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;


CREATE TABLE IF NOT EXISTS cover_images (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(150) NOT NULL,
    image_url  VARCHAR(500) NOT NULL,
    keywords   VARCHAR(500) NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    active     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_cover_images_active_sort (active, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Thêm cột ảnh bìa cho trips (chạy lại không lỗi)
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trips' AND COLUMN_NAME = 'cover_image_url');
SET @ddl := IF(@has_col = 0,
               'ALTER TABLE trips ADD COLUMN cover_image_url VARCHAR(500) NULL',
               'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 20 ảnh mẫu (chỉ chèn khi bảng còn trống để chạy lại không bị nhân đôi)
INSERT INTO cover_images (title, image_url, keywords, sort_order)
SELECT * FROM (
    SELECT 'Đà Lạt' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451417/du-lich-da-lat-ivivu_wr2so6.jpg' AS image_url, 'da lat,dalat,lam dong' AS keywords, 10 AS sort_order
    UNION ALL
    SELECT 'Đà Nẵng' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451415/danang_wusutk.jpg' AS image_url, 'da nang,danang' AS keywords, 20 AS sort_order
    UNION ALL
    SELECT 'Hội An' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451419/hoian_osibqf.jpg' AS image_url, 'hoi an,hoian,quang nam' AS keywords, 30 AS sort_order
    UNION ALL
    SELECT 'Huế' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451420/hue_uve5na.jpg' AS image_url, 'hue,co do hue,thua thien hue' AS keywords, 40 AS sort_order
    UNION ALL
    SELECT 'Hà Nội' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451418/hanoi_dtlzp6.jpg' AS image_url, 'ha noi,hanoi' AS keywords, 50 AS sort_order
    UNION ALL
    SELECT 'Sa Pa' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451411/sapa_zqgnj4.jpg' AS image_url, 'sa pa,sapa,lao cai,fansipan' AS keywords, 60 AS sort_order
    UNION ALL
    SELECT 'Vịnh Hạ Long' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451412/vinhalong_gv0hxj.jpg' AS image_url, 'ha long,halong,quang ninh,vinh ha long' AS keywords, 70 AS sort_order
    UNION ALL
    SELECT 'Ninh Bình' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451423/ninhbinh_bhzjnh.jpg' AS image_url, 'ninh binh,trang an,tam coc' AS keywords, 80 AS sort_order
    UNION ALL
    SELECT 'Phú Quốc' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451410/phuquoc_tnnxfw.jpg' AS image_url, 'phu quoc,kien giang' AS keywords, 90 AS sort_order
    UNION ALL
    SELECT 'Nha Trang' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451422/nhatrang_i4zkcz.jpg' AS image_url, 'nha trang,khanh hoa,cam ranh' AS keywords, 100 AS sort_order
    UNION ALL
    SELECT 'Vũng Tàu' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451413/vungtau_qt9vjn.jpg' AS image_url, 'vung tau,ba ria' AS keywords, 110 AS sort_order
    UNION ALL
    SELECT 'TP. Hồ Chí Minh' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451418/hcm_fsrh3q.jpg' AS image_url, 'ho chi minh,sai gon,saigon,tp hcm,tphcm,hcm' AS keywords, 120 AS sort_order
    UNION ALL
    SELECT 'Cần Thơ' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451414/cantho_zdqk0p.jpg' AS image_url, 'can tho,mien tay,dong bang song cuu long' AS keywords, 130 AS sort_order
    UNION ALL
    SELECT 'Mũi Né' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451421/muine_wg5klb.jpg' AS image_url, 'mui ne,phan thiet,binh thuan' AS keywords, 140 AS sort_order
    UNION ALL
    SELECT 'Quy Nhơn' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451411/quynhon_grv0iq.jpg' AS image_url, 'quy nhon,binh dinh,ky co' AS keywords, 150 AS sort_order
    UNION ALL
    SELECT 'Côn Đảo' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451415/condao_fsrp6z.jpg' AS image_url, 'con dao' AS keywords, 160 AS sort_order
    UNION ALL
    SELECT 'Hà Giang' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451417/hagiang_yap0ss.jpg' AS image_url, 'ha giang,dong van,quan ba' AS keywords, 170 AS sort_order
    UNION ALL
    SELECT 'Mộc Châu' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451420/mocchau_k6h0di.jpg' AS image_url, 'moc chau,son la' AS keywords, 180 AS sort_order
    UNION ALL
    SELECT 'Phong Nha' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451410/phongnha_x8lzlo.jpg' AS image_url, 'phong nha,quang binh,ke bang' AS keywords, 190 AS sort_order
    UNION ALL
    SELECT 'Cát Bà' AS title, 'https://res.cloudinary.com/dcke9stsl/image/upload/v1795451414/catba_hotnli.jpg' AS image_url, 'cat ba,hai phong,lan ha' AS keywords, 200 AS sort_order
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM cover_images);

-- UPDATE travel_platform_db.users SET role = 'ROLE_ADMIN' WHERE email = 'tranhuuloc05@gmail.com';

CREATE TABLE IF NOT EXISTS notifications (
                                             id            BIGINT       NOT NULL AUTO_INCREMENT,
                                             recipient_id  BIGINT       NOT NULL,
                                             actor_id      BIGINT       NULL,
                                             type          VARCHAR(30)  NOT NULL,
    target_type   VARCHAR(30)  NULL,
    target_id     BIGINT       NULL,
    link          VARCHAR(255) NULL,
    message       VARCHAR(500) NULL,
    actor_count   INT          NOT NULL DEFAULT 1,
    is_read       TINYINT(1)   NOT NULL DEFAULT 0,
    dedupe_key    VARCHAR(120) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- Chống trùng / gộp theo từng người nhận (NULL không bị ràng buộc)
    UNIQUE KEY uk_notifications_recipient_dedupe (recipient_id, dedupe_key),
    KEY idx_notifications_recipient_event (recipient_id, event_at, id),
    KEY idx_notifications_recipient_unread (recipient_id, is_read),
    KEY idx_notifications_cleanup (is_read, event_at),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

