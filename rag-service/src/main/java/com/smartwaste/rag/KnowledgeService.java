package com.smartwaste.rag;

import org.slf4j.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class KnowledgeService {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeService.class);
    private final KnowledgeRepository repository;
    private final SearchIndexes indexes;
    private final ChineseChunker chunker;
    public KnowledgeService(KnowledgeRepository repository, SearchIndexes indexes, ChineseChunker chunker) {
        this.repository=repository; this.indexes=indexes; this.chunker=chunker;
    }
    public record Mutation(Knowledge.Document document, List<String> warnings) { }
    public synchronized Mutation save(String id,Knowledge.Input input) throws Exception {
        Knowledge.Document old=id==null?null:repository.get(id);
        if(old==null && repository.all().size()>=200) throw new IllegalArgumentException("单实例最多维护 200 篇知识文档");
        String documentId=old==null?UUID.randomUUID().toString():old.id();
        String revision=UUID.randomUUID().toString();
        var chunks=chunker.split(documentId,revision,input);
        var document=new Knowledge.Document(documentId,revision,input.title().strip(),input.category(),input.source().strip(),
                input.content(),Instant.now().toString(),chunks);
        try {
            indexes.upsert(chunks);
            repository.save(document);
        } catch(Exception ex) {
            cleanup(chunks);
            throw new IllegalStateException("知识索引失败，原版本保留；请检查向量模型、Elasticsearch 和 Chroma 配置后重试",ex);
        }
        var warnings=old==null?List.<String>of():cleanup(old.chunks());
        return new Mutation(document,warnings);
    }
    public synchronized List<String> delete(String id) throws Exception {
        // 先取消发布。即使索引清理失败，检索白名单也不会再引用已删除内容。
        return cleanup(repository.delete(id).chunks());
    }
    private List<String> cleanup(List<Knowledge.Chunk> chunks) {
        try { indexes.delete(chunks); return List.of(); }
        catch(Exception ex) {
            log.warn("旧版本索引清理失败，已从有效知识白名单排除: {}",ex.getClass().getSimpleName());
            return List.of("旧版本索引清理未完成，已从问答中排除；请检查服务连接后清理旧索引存储");
        }
    }
    public List<Knowledge.Summary> list() {
        return repository.all().stream().sorted(Comparator.comparing(Knowledge.Document::updatedAt).reversed())
                .map(d->new Knowledge.Summary(d.id(),d.title(),d.category(),d.source(),d.updatedAt(),d.chunks().size())).toList();
    }
    public synchronized Map<String,Object> seed() throws Exception {
        int imported=0,skipped=0;
        for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md")) {
            String source="内置示例/"+resource.getFilename();
            if(repository.all().stream().anyMatch(d->d.source().equals(source))) { skipped++; continue; }
            String text;
            try(var stream=resource.getInputStream()) { text=new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
            String title=text.lines().findFirst().orElse("垃圾分类知识").replaceFirst("^#+\\s*","");
            String category=resource.getFilename().split("-")[0];
            save(null,new Knowledge.Input(title,category,source,text)); imported++;
        }
        return Map.of("imported",imported,"skipped",skipped);
    }
    public synchronized Mutation reindex(String id) throws Exception {
        var old=repository.get(id);
        return save(id,new Knowledge.Input(old.title(),old.category(),old.source(),old.content()));
    }
}
