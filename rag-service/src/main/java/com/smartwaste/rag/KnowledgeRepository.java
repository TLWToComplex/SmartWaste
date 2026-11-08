package com.smartwaste.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 单服务实例的持久化知识原文。双索引写成功后才原子发布新版本。 */
@Repository
public class KnowledgeRepository {
    private final Path directory;
    private final ObjectMapper json;
    private final Map<String,Knowledge.Document> documents = new HashMap<>();
    public KnowledgeRepository(RagProperties properties, ObjectMapper json) throws IOException {
        this.json=json;
        directory=Path.of(properties.dataDir()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        try (var files=Files.list(directory)) {
            for (Path file: files.filter(p->p.getFileName().toString().endsWith(".json")).toList()) {
                var document=json.readValue(file.toFile(),Knowledge.Document.class);
                documents.put(document.id(),document);
            }
        }
    }
    public synchronized List<Knowledge.Document> all() { return List.copyOf(documents.values()); }
    public synchronized Knowledge.Document get(String id) {
        var value=documents.get(id);
        if(value==null) throw new NoSuchElementException("知识文档不存在");
        return value;
    }
    public synchronized Map<String,Knowledge.Chunk> activeChunks(String category) {
        Map<String,Knowledge.Chunk> result=new HashMap<>();
        documents.values().stream().filter(d->category==null||category.isBlank()||category.equals(d.category()))
                .flatMap(d->d.chunks().stream()).forEach(c->result.put(c.id(),c));
        return result;
    }
    public synchronized void save(Knowledge.Document document) throws IOException {
        UUID.fromString(document.id());
        Path pending=Files.createTempFile(directory,"knowledge-",".tmp");
        try {
            json.writeValue(pending.toFile(),document);
            try { Files.move(pending,directory.resolve(document.id()+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(pending,directory.resolve(document.id()+".json"),StandardCopyOption.REPLACE_EXISTING); }
            documents.put(document.id(),document);
        } finally { Files.deleteIfExists(pending); }
    }
    public synchronized Knowledge.Document delete(String id) throws IOException {
        var old=get(id);
        Files.delete(directory.resolve(old.id()+".json"));
        documents.remove(id);
        return old;
    }
}
