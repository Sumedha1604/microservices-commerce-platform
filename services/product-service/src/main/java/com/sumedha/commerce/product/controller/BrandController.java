package com.sumedha.commerce.product.controller; import com.sumedha.commerce.product.dto.request.*; import com.sumedha.commerce.product.dto.response.*; import com.sumedha.commerce.product.service.BrandService; import com.sumedha.commerce.common.core.api.ApiResponse; import jakarta.validation.Valid; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/v1/brands") public class BrandController {private final BrandService service;public BrandController(BrandService service){this.service=service;}
@PostMapping public ResponseEntity<ApiResponse<BrandResponse>> create(@Valid @RequestBody CreateBrandRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(r)));}
@GetMapping public ApiResponse<List<BrandResponse>> list(){return ApiResponse.success(service.list());}
@GetMapping("/{brandId}") public ApiResponse<BrandResponse> get(@PathVariable("brandId") UUID brandId){return ApiResponse.success(service.get(brandId));}
@PutMapping("/{brandId}") public ApiResponse<BrandResponse> update(@PathVariable("brandId") UUID brandId,@Valid @RequestBody UpdateBrandRequest r){return ApiResponse.success(service.update(brandId,r));}
@DeleteMapping("/{brandId}") public ResponseEntity<Void> delete(@PathVariable("brandId") UUID brandId){service.delete(brandId);return ResponseEntity.noContent().build();}
}
