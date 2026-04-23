package com.comatching.item.domain.product.service;

import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;

public interface AdminProductService {

	ProductResponse createProduct(ProductCreateRequest request);
}
