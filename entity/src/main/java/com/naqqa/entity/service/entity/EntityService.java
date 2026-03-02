package com.naqqa.entity.service.entity;

import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.repository.mongo.EntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EntityService {

    private final EntityRepository entityRepository;

    public Entity save(Entity entity) {
        return entityRepository.save(entity);
    }

    public Entity findById(String id) {
        return entityRepository.findById(id).orElse(null);
    }

    public List<Entity> findAll() {
        return entityRepository.findAll();
    }

    public void delete(String id) {
        entityRepository.deleteById(id);
    }

    public List<Entity> saveAll(List<Entity> entities) {
        return entityRepository.saveAll(entities);
    }
}
