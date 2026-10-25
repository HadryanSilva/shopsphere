package br.com.hadryan.shopsphere.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import br.com.hadryan.shopsphere.catalog.repository.ProductRepository;
import br.com.hadryan.shopsphere.catalog.repository.CategoryRepository;
import br.com.hadryan.shopsphere.catalog.model.Category;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.dao.DataIntegrityViolationException;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogServiceApplicationTests {
    @Autowired private MockMvc mvc;

    @Test
    void createdCategoryLocationPointsToReturnedResource() throws Exception {
        var categoryId = UUID.randomUUID();
        var saved = new Category();
        saved.setName("Books");
        saved.setDescription("Reading");
        ReflectionTestUtils.setField(saved, "id", categoryId);
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(saved));

        var created = mvc.perform(post("/api/v1/categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"name\":\"Books\",\"description\":\"Reading\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/categories/" + categoryId))
                .andExpect(jsonPath("$.id").value(categoryId.toString()))
                .andExpect(jsonPath("$.name").value("Books"))
                .andExpect(jsonPath("$.description").value("Reading"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        mvc.perform(get(created.getResponse().getHeader("Location")))
                .andExpect(status().isOk())
                .andExpect(content().json(created.getResponse().getContentAsString()));
        verify(categoryRepository).save(any(Category.class));
        verify(categoryRepository).findById(categoryId);
    }

    @Test
    void missingProductReturnsNotFoundProblem() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.detail").value("Product not found"));
        verify(productRepository).findById(id);
    }

    @Test
    void missingCategoryReturnsNotFoundProblem() throws Exception {
        mvc.perform(get("/api/v1/categories/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidPriceRangeIsBadRequestInsteadOfInternalError() throws Exception {
        mvc.perform(get("/api/v1/products").param("minPrice", "20").param("maxPrice", "10"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(productRepository);
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalMessage() throws Exception {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenThrow(new RuntimeException("SQL private-password"));
        mvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("private-password"))));
    }

    @Test
    void unsupportedSortIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("sort", "unsupported,asc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(productRepository);
    }

    @Test
    void patchOfMissingProductIsNotFound() throws Exception {
        mvc.perform(patch("/api/v1/products/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"name\":\"Product\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void missingCategoryReferenceIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"name\":\"Product\",\"categoryId\":\""
                                + UUID.randomUUID() + "\",\"price\":10,\"currency\":\"EUR\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Category not found"));
    }

    @Test
    void duplicateCategoryIsConflictWithoutSqlDetails() throws Exception {
        when(categoryRepository.save(any())).thenThrow(new DataIntegrityViolationException("SQL private-password"));
        mvc.perform(post("/api/v1/categories").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"name\":\"Category\",\"description\":\"Description\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(content().string(not(containsString("private-password"))));
    }

    @MockitoBean private ProductRepository productRepository;
    @MockitoBean private CategoryRepository categoryRepository;
    @MockitoBean private JwtDecoder jwtDecoder;

	@Test
	void contextLoads() {
	}

}
