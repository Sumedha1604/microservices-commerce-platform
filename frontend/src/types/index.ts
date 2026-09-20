export type UserRole = 'CUSTOMER' | 'ADMIN' | 'SUPPORT';
export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'DISCONTINUED';
export interface ApiResponse<T> { success: boolean; message: string; data: T; timestamp: string }
export interface PageResponse<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean; hasPrevious: boolean }
export interface ApiErrorBody { errorCode: string; message: string; statusCode: number; timestamp?: string }
export interface Tokens { accessToken: string; refreshToken: string; tokenType: string }
export interface AuthUser { userId: string; email: string; role: UserRole; verified: boolean }
export interface AuthResponse extends AuthUser { tokens: Tokens }
export interface Category { categoryId: string; name: string; slug: string; description: string | null; parentCategoryId: string | null; active: boolean }
export interface Brand { brandId: string; name: string; slug: string; description: string | null; active: boolean }
export interface ProductImage { imageId: string; url: string; altText: string; sortOrder: number; primaryImage: boolean }
export interface ProductAttribute { attributeId: string; name: string; value: string }
export interface ProductSummary { productId: string; sku: string; name: string; slug: string; price: number; currency: string }
export interface Product extends ProductSummary { shortDescription: string | null; description: string | null; category: Category; brand: Brand | null; status: ProductStatus; active: boolean; images: ProductImage[]; attributes: ProductAttribute[]; createdAt: string; updatedAt: string }
export interface SearchProduct extends ProductSummary { shortDescription: string | null; description: string | null; categoryId: string; brandId: string | null; status: ProductStatus; version: number; updatedAt: string; indexedAt: string }
export interface Recommendation extends Pick<ProductSummary, 'productId'|'name'|'slug'|'price'|'currency'> { categoryId: string; brandId: string | null; score: number; reasons: ('SAME_CATEGORY'|'SAME_BRAND'|'SIMILAR_PRICE')[] }
export interface Recommendations { sourceProductId: string; strategy: 'CONTENT_BASED_V1'; limit: number; items: Recommendation[] }
export interface CartItem { id: string; productId: string; quantity: number; createdAt: string; updatedAt: string }
export interface Cart { id: string; userId: string; items: CartItem[]; createdAt: string; updatedAt: string }
export interface Checkout { cartId: string; orderId: string; paymentId: string; orderStatus: string; paymentStatus: string; total: number; currency: string }
export interface OrderItem { id: string; productId: string; productName: string; sku: string | null; unitPrice: number; quantity: number; lineTotal: number; createdAt: string }
export interface Order { id: string; userId: string; status: 'PENDING'|'CONFIRMED'|'CANCELLED'; subtotal: number; total: number; currency: string; items: OrderItem[]; createdAt: string; updatedAt: string }
export interface Notification { notificationId: string; eventId: string; eventType: string; orderId: string; paymentId: string; userId: string; channel: 'INTERNAL'; notificationType: 'PAYMENT_AUTHORIZED'|'PAYMENT_FAILED'; subject: string; message: string; status: 'CREATED'; occurredAt: string; createdAt: string; updatedAt: string }
export interface Profile { profileId: string; authUserId: string; firstName: string; lastName: string; phone: string | null; avatarUrl: string | null; dateOfBirth: string | null }
export interface Address { addressId: string; type: 'SHIPPING'|'BILLING'; recipientName: string | null; line1: string; line2: string | null; city: string; stateRegion: string | null; postalCode: string; countryCode: string | null; phone: string | null; isDefault: boolean }
export interface Preferences { language: string | null; currency: string | null; marketingEmails: boolean; orderNotifications: boolean }
export type DeadLetterStatus = 'NEW'|'REPLAYED'|'REPLAY_FAILED';
export interface DeadLetter { id: string; eventId: string | null; eventType: string | null; schemaVersion: number | null; orderId: string | null; originalTopic: string; originalPartition: number; originalOffset: number; dltTopic: string; dltPartition: number; dltOffset: number; dltTimestamp: string; eventKey: string | null; exceptionClass: string; exceptionMessage: string; consumerGroup: string; traceparent: string | null; firstSeenAt: string; status: DeadLetterStatus; replayedAt: string | null; replayCount: number; lastReplayError: string | null }
export interface DeadLetterDetail { event: DeadLetter; payload: string }
export interface ProductWrite { sku?: string; name: string; slug: string; categoryId: string; brandId: string | null; price: number; currency: string; shortDescription?: string | null; description?: string | null; status?: ProductStatus; active?: boolean }
