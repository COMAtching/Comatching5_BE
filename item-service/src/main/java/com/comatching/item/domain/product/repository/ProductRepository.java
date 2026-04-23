package com.comatching.item.domain.product.repository;

import com.comatching.item.domain.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
	// 판매 중인 상품만 조회
	List<Product> findByIsActiveTrue();
}