package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    List<Product> getAll();

    @Query("SELECT * FROM products WHERE name LIKE :search OR category LIKE :search OR assetTag LIKE :search")
    List<Product> searchProducts(String search);

    @Insert
    long insert(Product product);

    @Update
    void update(Product product);

    @Delete
    void delete(Product product);

    @Query("SELECT IFNULL(SUM(quantity), 0) FROM products")
    int getTotalQuantity();

    @Query("SELECT COUNT(*) FROM products WHERE quantity < 5 AND quantity > 0")
    int getLowStockCount();

    @Query("SELECT COUNT(*) FROM products WHERE quantity = 0")
    int getOutOfStockCount();

    @Query("SELECT * FROM products WHERE id = :id")
    Product getById(int id);

    @Query("DELETE FROM products")
    void deleteAll();

    @Query("SELECT IFNULL(SUM(quantity * unitPrice), 0.0) FROM products")
    double getTotalStockValue();

    @Query("SELECT * FROM products WHERE quantity = 0")
    List<Product> getOutOfStockProducts();

    @Query("SELECT category, SUM(quantity) as total FROM products GROUP BY category")
    List<CategoryCount> getCountByCategory();

    class CategoryCount {
        public String category;
        public int total;
    }
}
