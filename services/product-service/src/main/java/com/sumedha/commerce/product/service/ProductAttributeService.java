package com.sumedha.commerce.product.service; import com.sumedha.commerce.product.dto.request.*; import com.sumedha.commerce.product.dto.response.*; import java.util.*;
public interface ProductAttributeService {ProductAttributeResponse create(UUID productId,CreateProductAttributeRequest r);List<ProductAttributeResponse> list(UUID productId);void delete(UUID productId,UUID attributeId);}
