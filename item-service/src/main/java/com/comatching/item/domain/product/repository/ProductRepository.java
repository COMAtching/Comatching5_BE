package com.comatching.item.domain.product.repository;

import com.comatching.item.domain.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

	@Query("""
		SELECT DISTINCT p
		FROM Product p
		LEFT JOIN FETCH p.rewards
		WHERE p.isActive = true
		ORDER BY p.displayOrder ASC, p.id ASC
		""")
	List<Product> findActiveProductsWithRewards();

	@Query("""
		SELECT DISTINCT p
		FROM Product p
		LEFT JOIN FETCH p.rewards
		ORDER BY p.displayOrder ASC, p.id ASC
		""")
	List<Product> findAllProductsWithRewards();

	@Query("""
		SELECT DISTINCT p
		FROM Product p
		LEFT JOIN FETCH p.bonusRewards
		WHERE p.id IN :productIds
		""")
	List<Product> fetchBonusRewardsByProductIds(@Param("productIds") List<Long> productIds);
}
