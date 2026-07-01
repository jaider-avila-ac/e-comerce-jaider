package jaider.ecommerce.tienda.banner;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.List;

@RestController
@RequestMapping("/api/v1/banners")
@RequiredArgsConstructor
public class BannerController {

    private final BannerService service;

    @GetMapping
    public List<BannerResponse> getAll() {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BannerResponse create(@RequestBody BannerRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public BannerResponse update(@PathVariable Long id, @RequestBody BannerRequest req) {
        return service.update(id, req);
    }

    @PatchMapping("/reordenar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reordenar(@RequestBody List<Long> ids) {
        service.reordenar(ids);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
