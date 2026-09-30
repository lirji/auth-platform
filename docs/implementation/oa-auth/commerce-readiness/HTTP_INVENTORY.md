# 商城HTTP入口实测源码清单

基线 commerce-platform 31dbdcd；当前任务仅增加静态 `/operations/catalog` 壳。逐个注解展开多路径，共 218 条。这是源码清单，不代表中央权限已接管；路径参数action还需业务枚举细分。

| Controller | 方法 | 路径 | 当前入口身份 | 源码 |
|---|---|---|---|---|
| AftersaleController | GET | `/v1/orders/{id}/fulfillment` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:28` |
| AftersaleController | GET | `/v1/admin/fulfillments` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:34` |
| AftersaleController | POST | `/v1/admin/fulfillments/{id}/ship` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:41` |
| AftersaleController | POST | `/v1/admin/fulfillments/{id}/deliver` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:48` |
| AftersaleController | POST | `/v1/aftersales` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:55` |
| AftersaleController | GET | `/v1/aftersales/{id}` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:62` |
| AftersaleController | GET | `/v1/aftersales` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:68` |
| AftersaleController | GET | `/v1/admin/aftersales` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:75` |
| AftersaleController | POST | `/v1/admin/aftersales/{id}/approve` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:82` |
| AftersaleController | POST | `/v1/admin/aftersales/{id}/reject` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:89` |
| AftersaleController | POST | `/v1/admin/aftersales/{id}/receive-return` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:96` |
| AftersaleController | GET | `/v1/admin/refunds` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:103` |
| AftersaleController | POST | `/v1/admin/refunds/{id}/reconcile` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:110` |
| AftersaleController | POST | `/v1/admin/sandbox/refunds/{id}/success` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java:116` |
| CouponController | POST | `/v1/admin/coupon-definitions` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java:20` |
| CouponController | GET | `/v1/coupon-definitions` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java:27` |
| CouponController | GET | `/v1/admin/coupon-definitions` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java:27` |
| CouponController | POST | `/v1/coupons/{id}/{version}/claim` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java:34` |
| CouponController | GET | `/v1/coupons` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java:41` |
| EntitlementController | POST | `/v1/admin/entitlement-definitions` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:20` |
| EntitlementController | GET | `/v1/admin/entitlement-definitions` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:27` |
| EntitlementController | GET | `/v1/entitlements` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:34` |
| EntitlementController | GET | `/v1/admin/entitlements` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:41` |
| EntitlementController | POST | `/v1/entitlements/{id}/consume` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:48` |
| EntitlementController | GET | `/v1/entitlements/{id}/ledger` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:55` |
| EntitlementController | POST | `/v1/admin/entitlements/{id}/resolve` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java:62` |
| PointOfferController | POST | `/v1/admin/point-offers` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java:20` |
| PointOfferController | POST | `/v1/admin/point-offers/{id}/status` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java:27` |
| PointOfferController | GET | `/v1/admin/point-offers` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java:34` |
| PointOfferController | GET | `/v1/point-offers` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java:42` |
| PointOfferController | POST | `/v1/point-offers/{id}/redeem` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java:49` |
| PointOfferController | GET | `/v1/point-redemptions` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java:56` |
| CatalogSchedulingController | POST | `/v1/operations/catalog-jobs` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:24` |
| CatalogSchedulingController | GET | `/v1/operations/catalog-jobs` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:31` |
| CatalogSchedulingController | GET | `/v1/operations/catalog-jobs/{id}/items` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:38` |
| CatalogSchedulingController | POST | `/v1/operations/catalog-jobs/{id}/control` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:45` |
| CatalogSchedulingController | POST | `/v1/operations/catalog-jobs/pump` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:52` |
| CatalogSchedulingController | GET | `/v1/operations/skus/{id}/channel-prices` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:58` |
| CatalogSchedulingController | POST | `/v1/operations/skus/{id}/channel-prices` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:64` |
| CatalogSchedulingController | GET | `/v1/operations/skus/{id}/channel-prices/{channel}/history` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java:71` |
| CatalogMerchandisingController | POST | `/v1/operations/catalog-categories` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:20` |
| CatalogMerchandisingController | POST | `/v1/operations/catalog-categories/{id}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:27` |
| CatalogMerchandisingController | GET | `/v1/operations/catalog-categories` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:34` |
| CatalogMerchandisingController | GET | `/v1/catalog/categories` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:41` |
| CatalogMerchandisingController | POST | `/v1/operations/specification-templates` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:48` |
| CatalogMerchandisingController | GET | `/v1/operations/specification-templates` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:55` |
| CatalogMerchandisingController | GET | `/v1/operations/specification-templates/{id}/{version}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:62` |
| CatalogMerchandisingController | GET | `/v1/operations/products/{id}/merchandising` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:69` |
| CatalogMerchandisingController | POST | `/v1/operations/products/{id}/merchandising` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:75` |
| CatalogMerchandisingController | GET | `/v1/operations/skus/{id}/barcode` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:82` |
| CatalogMerchandisingController | POST | `/v1/operations/skus/{id}/barcode` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:88` |
| CatalogMerchandisingController | GET | `/v1/operations/catalog-search` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:95` |
| CatalogMerchandisingController | GET | `/v1/catalog/search` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:106` |
| CatalogMerchandisingController | GET | `/v1/catalog/items/{id}` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java:116` |
| ProductOperationsController | POST | `/v1/operations/products` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:20` |
| ProductOperationsController | POST | `/v1/operations/products/{id}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:27` |
| ProductOperationsController | GET | `/v1/operations/products` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:34` |
| ProductOperationsController | POST | `/v1/operations/skus` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:41` |
| ProductOperationsController | POST | `/v1/operations/skus/{id}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:48` |
| ProductOperationsController | GET | `/v1/operations/skus` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:55` |
| ProductOperationsController | GET | `/v1/operations/skus/{id}/history` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java:62` |
| CommerceController | GET | `/v1/me` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:40` |
| CommerceController | POST | `/v1/admin/members` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:45` |
| CommerceController | GET | `/v1/admin/members` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:51` |
| CommerceController | POST | `/v1/admin/merchants` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:57` |
| CommerceController | GET | `/v1/admin/merchants` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:63` |
| CommerceController | POST | `/v1/admin/stores` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:69` |
| CommerceController | GET | `/v1/admin/stores` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:75` |
| CommerceController | POST | `/v1/admin/skus` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:81` |
| CommerceController | GET | `/v1/catalog` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:87` |
| CommerceController | GET | `/v1/admin/skus` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:87` |
| CommerceController | POST | `/v1/admin/campaigns` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:93` |
| CommerceController | GET | `/v1/admin/campaigns` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:99` |
| CommerceController | POST | `/v1/admin/campaigns/{id}/{version}/publish` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:108` |
| CommerceController | POST | `/v1/admin/campaigns/{id}/{version}/pause` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:114` |
| CommerceController | POST | `/v1/quotes` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:120` |
| CommerceController | GET | `/v1/quotes/{id}` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java:126` |
| MarketingAssetController | POST | `/v1/admin/campaigns/{id}/{version}/preview` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:29` |
| MarketingAssetController | POST | `/v1/admin/audiences` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:36` |
| MarketingAssetController | GET | `/v1/admin/audiences` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:43` |
| MarketingAssetController | POST | `/v1/admin/rules` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:50` |
| MarketingAssetController | POST | `/v1/admin/rules/{id}/{version}/publish` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:57` |
| MarketingAssetController | GET | `/v1/admin/rules` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:64` |
| MarketingAssetController | GET | `/v1/admin/rule-fields` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:71` |
| MarketingAssetController | POST | `/v1/admin/campaigns/{id}/{version}/{action:submit&#124;approve&#124;reject}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:78` |
| MarketingAssetController | GET | `/v1/admin/campaign-budgets` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java:86` |
| CouponDeliveryController | POST | `/v1/admin/coupon-deliveries` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java:20` |
| CouponDeliveryController | GET | `/v1/admin/coupon-deliveries` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java:27` |
| CouponDeliveryController | GET | `/v1/admin/coupon-deliveries/{id}/recipients` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java:34` |
| CouponDeliveryController | POST | `/v1/admin/coupon-deliveries/{id}/control` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java:41` |
| CouponDeliveryController | POST | `/v1/admin/coupon-deliveries/pump` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java:48` |
| CampaignExecutionController | GET | `/v1/admin/marketing-executions` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/execution/CampaignExecutionController.java:23` |
| CampaignExecutionController | GET | `/v1/admin/marketing-executions/{orderId}/{campaignId}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/execution/CampaignExecutionController.java:29` |
| MarketingEffectsController | GET | `/v1/admin/marketing-effects` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java:25` |
| MarketingEffectsController | GET | `/v1/admin/marketing-effects/journeys` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java:33` |
| MarketingEffectsController | GET | `/v1/admin/marketing-effects/deliveries` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java:41` |
| MarketingEffectsController | POST | `/v1/admin/marketing-effects/rebuild` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java:49` |
| MarketingEffectsController | GET | `/v1/admin/journey-effects` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java:56` |
| JourneyController | POST | `/v1/admin/journeys` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:23` |
| JourneyController | GET | `/v1/admin/journeys` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:30` |
| JourneyController | POST | `/v1/admin/journeys/validate` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:37` |
| JourneyController | POST | `/v1/admin/journeys/{id}/{version}/preview` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:43` |
| JourneyController | POST | `/v1/admin/journeys/{id}/{version}/{action}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:50` |
| JourneyController | POST | `/v1/admin/journey-instances` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:58` |
| JourneyController | GET | `/v1/admin/journey-instances` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:65` |
| JourneyController | GET | `/v1/journey-instances` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:65` |
| JourneyController | GET | `/v1/admin/journey-instances/{id}/history` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:72` |
| JourneyController | GET | `/v1/journey-instances/{id}/history` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:72` |
| JourneyController | POST | `/v1/admin/journey-instances/{id}/{action}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:79` |
| JourneyController | POST | `/v1/admin/journeys/pump` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:86` |
| JourneyController | GET | `/v1/admin/journey-scans` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:92` |
| JourneyController | POST | `/v1/admin/journey-scans/{id}/{version}/retry` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:99` |
| JourneyController | GET | `/v1/notifications` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java:106` |
| SegmentController | POST | `/v1/admin/segments` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:20` |
| SegmentController | GET | `/v1/admin/segments` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:27` |
| SegmentController | POST | `/v1/admin/segments/{id}/schedule` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:34` |
| SegmentController | POST | `/v1/admin/segments/{id}/refresh` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:41` |
| SegmentController | GET | `/v1/admin/segments/{id}/runs` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:48` |
| SegmentController | POST | `/v1/admin/segments/pump` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:55` |
| SegmentController | POST | `/v1/admin/segment-runs/{id}/{action}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java:61` |
| MemberBehaviorController | GET | `/v1/admin/member-behavior/{id}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:39` |
| MemberBehaviorController | POST | `/v1/admin/member-behavior/{id}/profile` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:46` |
| MemberBehaviorController | GET | `/v1/admin/member-behavior/{id}/events` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:54` |
| MemberBehaviorController | GET | `/v1/members/me/behavior` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:62` |
| MemberBehaviorController | POST | `/v1/members/me/behavior/profile` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:68` |
| MemberBehaviorController | POST | `/v1/members/me/behavior/events` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:75` |
| MemberBehaviorController | GET | `/v1/members/me/behavior/events` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:91` |
| MemberBehaviorController | POST | `/v1/admin/member-behavior/rebuild` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java:104` |
| MemberBenefitController | POST | `/v1/admin/member-cycle-benefits` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/benefit/MemberBenefitController.java:20` |
| MemberBenefitController | GET | `/v1/admin/member-cycle-benefits` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/benefit/MemberBenefitController.java:27` |
| MemberBenefitController | POST | `/v1/admin/member-cycle-benefits/{id}/grant` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/benefit/MemberBenefitController.java:33` |
| MemberCycleController | POST | `/v1/admin/member-cycles/policies` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java:20` |
| MemberCycleController | GET | `/v1/admin/member-cycles/policies` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java:27` |
| MemberCycleController | POST | `/v1/admin/member-cycles/{id}/evaluate` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java:34` |
| MemberCycleController | GET | `/v1/admin/member-cycles/{id}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java:41` |
| MemberCycleController | GET | `/v1/members/me/cycle` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java:48` |
| MemberGrowthController | POST | `/v1/admin/member-growth/policies` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:24` |
| MemberGrowthController | GET | `/v1/admin/member-growth/policies` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:31` |
| MemberGrowthController | GET | `/v1/admin/member-growth/{id}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:38` |
| MemberGrowthController | GET | `/v1/admin/member-growth/{id}/ledger` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:44` |
| MemberGrowthController | POST | `/v1/admin/member-growth/{id}/adjust` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:51` |
| MemberGrowthController | POST | `/v1/admin/member-growth/{id}/recalculate` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:58` |
| MemberGrowthController | GET | `/v1/members/me/growth` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:65` |
| MemberGrowthController | GET | `/v1/members/me/growth/ledger` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:71` |
| MemberGrowthController | POST | `/v1/admin/member-tags` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:78` |
| MemberGrowthController | GET | `/v1/admin/member-tags` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:85` |
| MemberGrowthController | POST | `/v1/admin/member-tags/{id}/assign` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:92` |
| MemberGrowthController | GET | `/v1/admin/member-tags/{id}/assignments` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java:99` |
| MemberOperationsController | POST | `/v1/admin/members/{id}/profile` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberOperationsController.java:20` |
| MemberOperationsController | POST | `/v1/admin/members/{id}/status` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberOperationsController.java:27` |
| MemberOperationsController | GET | `/v1/admin/members/{id}/history` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberOperationsController.java:34` |
| MemberPointsController | POST | `/v1/admin/member-points/policies` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:20` |
| MemberPointsController | GET | `/v1/admin/member-points/policies` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:27` |
| MemberPointsController | GET | `/v1/admin/member-points/{id}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:34` |
| MemberPointsController | GET | `/v1/admin/member-points/{id}/ledger` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:41` |
| MemberPointsController | POST | `/v1/admin/member-points/{id}/adjust` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:49` |
| MemberPointsController | POST | `/v1/admin/member-points/{id}/expire` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:56` |
| MemberPointsController | GET | `/v1/members/me/points` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:63` |
| MemberPointsController | GET | `/v1/members/me/points/ledger` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java:69` |
| ConsoleController | GET | `/v1/runtime-capabilities` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java:38` |
| ConsoleController | GET | `/v1/stores` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java:44` |
| ConsoleController | GET | `/v1/admin/orders` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java:51` |
| ConsoleController | GET | `/v1/admin/orders/{id}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java:58` |
| ConsoleController | GET | `/v1/admin/orders/{id}/payment` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java:64` |
| ConsoleController | POST | `/v1/admin/orders/{id}/payment/reconcile` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java:70` |
| DashboardController | GET | `/v1/admin/dashboard` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/DashboardController.java:42` |
| OpsPageController | POST | `/v1/admin/ops-pages` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:23` |
| OpsPageController | GET | `/v1/admin/ops-pages` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:30` |
| OpsPageController | POST | `/v1/admin/ops-pages/preview` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:37` |
| OpsPageController | GET | `/v1/admin/ops-pages/{id}/render` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:43` |
| OpsPageController | GET | `/v1/admin/ops-pages/{id}/versions` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:49` |
| OpsPageController | POST | `/v1/admin/ops-pages/{id}/{version}/{action}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:55` |
| OpsPageController | POST | `/v1/admin/ops-pages/{id}/{version}/actions/{action}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java:63` |
| OrderController | POST | `/v1/orders` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java:24` |
| OrderController | GET | `/v1/orders/{id}` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java:31` |
| OrderController | GET | `/v1/orders` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java:37` |
| OrderController | POST | `/v1/orders/{id}/cancel` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java:44` |
| OrderController | POST | `/v1/admin/inventory/receipts` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java:51` |
| OrderController | GET | `/v1/admin/inventory` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java:58` |
| PaymentController | POST | `/v1/orders/{id}/payments` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:28` |
| PaymentController | GET | `/v1/orders/{id}/payment` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:35` |
| PaymentController | POST | `/v1/orders/{id}/payment/reconcile` | MEMBER_OR_ADMIN / explicit allowlist | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:41` |
| PaymentController | POST | `/v1/admin/sandbox/payments/{id}/fact` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:47` |
| PaymentController | POST | `/v1/admin/orders/expire` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:54` |
| PaymentController | POST | `/v1/admin/orders/{id}/expiry/retry` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:60` |
| PaymentController | POST | `/v1/admin/events/pump` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:67` |
| PaymentController | GET | `/v1/admin/events` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:73` |
| PaymentController | GET | `/v1/admin/events/health` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:80` |
| PaymentController | POST | `/v1/admin/events/{id}/retry` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java:86` |
| PlatformRuntimeController | GET | `/v1/platform/runtime` | PLATFORM_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/health/PlatformRuntimeController.java:20` |
| RuntimeRecoveryController | GET | `/v1/admin/runtime/work-types` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:27` |
| RuntimeRecoveryController | GET | `/v1/admin/runtime/stopped` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:33` |
| RuntimeRecoveryController | POST | `/v1/admin/runtime/recoveries` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:41` |
| RuntimeRecoveryController | GET | `/v1/admin/runtime/recoveries` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:48` |
| RuntimeRecoveryController | GET | `/v1/admin/runtime/replay/classifications` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:56` |
| RuntimeRecoveryController | POST | `/v1/admin/runtime/replay/dry-run` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:62` |
| RuntimeRecoveryController | POST | `/v1/admin/runtime/replays` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:67` |
| RuntimeRecoveryController | GET | `/v1/admin/runtime/replays` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:73` |
| RuntimeRecoveryController | GET | `/v1/admin/runtime/replays/{id}` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:79` |
| RuntimeRecoveryController | POST | `/v1/admin/runtime/replays/{id}/control` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java:85` |
| CentralPageController | GET | `/operations/catalog` | PUBLIC_STATIC | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralPageController.java:10` |
| CentralPageController | GET | `/operations/products` | PUBLIC_STATIC | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralPageController.java:10` |
| CentralPageController | GET | `/collaboration/products` | PUBLIC_STATIC | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralPageController.java:10` |
| CentralPageController | GET | `/iam/callback` | PUBLIC_STATIC | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralPageController.java:10` |
| CentralScopeController | GET | `/v1/operations/scoped/{type}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:18` |
| CentralScopeController | GET | `/v1/operations/scoped/{type}/resources/{id}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:21` |
| CentralScopeController | GET | `/v1/operations/scoped/{type}/resources/{id}/actions` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:24` |
| CentralScopeController | POST | `/v1/operations/scoped/{type}/resources/{id}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:27` |
| CentralScopeController | GET | `/v1/operations/scoped/{type}/export-access` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:32` |
| CentralScopeController | POST | `/v1/operations/scoped/{type}/exports` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:35` |
| CentralScopeController | POST | `/v1/operations/scoped/{type}/exports/{id}/start` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:38` |
| CentralScopeController | POST | `/v1/operations/scoped/{type}/exports/{id}/advance` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:41` |
| CentralScopeController | GET | `/v1/operations/scoped/{type}/exports/{id}` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:44` |
| CentralScopeController | GET | `/v1/operations/scoped/{type}/exports/{id}/download` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java:47` |
| StoreAccessController | POST | `/v1/admin/store-grants` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/StoreAccessController.java:26` |
| StoreAccessController | POST | `/v1/admin/store-grants/{id}/status` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/StoreAccessController.java:33` |
| StoreAccessController | GET | `/v1/admin/store-grants` | ADMIN | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/StoreAccessController.java:40` |
| StoreAccessController | GET | `/v1/operations/stores` | CENTRAL_OR_LEGACY_OPERATOR | `commerce-app/src/main/java/com/lrj/commerce/app/http/store/StoreAccessController.java:47` |
