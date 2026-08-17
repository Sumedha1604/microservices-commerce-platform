package com.sumedha.commerce.product.controller; import com.sumedha.commerce.product.dto.request.*; import com.sumedha.commerce.product.dto.response.*; import com.sumedha.commerce.product.service.CategoryService; import com.sumedha.commerce.common.core.api.ApiResponse; import jakarta.validation.Valid; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/v1/categories") public class CategoryController {private final CategoryService service;public CategoryController(CategoryService service){this.service=service;}
@PostMapping public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CreateCategoryRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(r)));}
@GetMapping public ApiResponse<List<CategoryResponse>> list(){return ApiResponse.success(service.list());}
@GetMapping("/{categoryId}") public ApiResponse<CategoryResponse> get(@PathVariable("categoryId") UUID categoryId){return ApiResponse.success(service.get(categoryId));}
@PutMapping("/{categoryId}") public ApiResponse<CategoryResponse> update(@PathVariable("categoryId") UUID categoryId,@Valid @RequestBody UpdateCategoryRequest r){return ApiResponse.success(service.update(categoryId,r));}
@DeleteMapping("/{categoryId}") public ResponseEntity<Void> delete(@PathVariable("categoryId") UUID categoryId){service.delete(categoryId);return ResponseEntity.noContent().build();}
}
