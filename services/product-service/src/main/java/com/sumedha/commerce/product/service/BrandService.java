package com.sumedha.commerce.product.service; import com.sumedha.commerce.product.dto.request.*; import com.sumedha.commerce.product.dto.response.*; import java.util.*;
public interface BrandService {BrandResponse create(CreateBrandRequest r);List<BrandResponse> list();BrandResponse get(UUID brandId);BrandResponse update(UUID brandId,UpdateBrandRequest r);void delete(UUID brandId);}
