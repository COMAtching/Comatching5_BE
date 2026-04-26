package com.comatching.item.domain.product.service;

import java.util.List;

import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;

public interface AdminProductService {

	ProductResponse createProduct(ProductCreateRequest request);

	List<ProductResponse> getProducts();

	void deleteProduct(Long productId);
}
