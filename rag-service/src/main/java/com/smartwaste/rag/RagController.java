package com.smartwaste.rag;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/internal/rag")
public class RagController {
    private final KnowledgeService knowledge;
    private final KnowledgeRepository repository;
    private final AnswerService answers;
    private final SearchIndexes indexes;
    private final RagProperties properties;
    private final QueryExpansionProperties queryProperties;
    private final QueryPlanner planner;
    public RagController(KnowledgeService knowledge,KnowledgeRepository repository,AnswerService answers,SearchIndexes indexes,
                         RagProperties properties,QueryExpansionProperties queryProperties,QueryPlanner planner) {
        this.knowledge=knowledge; this.repository=repository; this.answers=answers; this.indexes=indexes; this.properties=properties;
        this.queryProperties=queryProperties; this.planner=planner;
    }
    @PostMapping("/chat") public AnswerService.Answer chat(@Valid @RequestBody AnswerService.Question question) { return answers.ask(question); }
    @GetMapping("/documents") public List<Knowledge.Summary> list() { return knowledge.list(); }
    @GetMapping("/documents/{id}") public Knowledge.Document get(@PathVariable String id) { return repository.get(id); }
    @PostMapping("/documents") public KnowledgeService.Mutation create(@Valid @RequestBody Knowledge.Input input) throws Exception { return knowledge.save(null,input); }
    @PutMapping("/documents/{id}") public KnowledgeService.Mutation update(@PathVariable String id,@Valid @RequestBody Knowledge.Input input) throws Exception { return knowledge.save(id,input); }
    @DeleteMapping("/documents/{id}") public Map<String,Object> delete(@PathVariable String id) throws Exception { return Map.of("warnings",knowledge.delete(id)); }
    @PostMapping("/documents/{id}/reindex") public KnowledgeService.Mutation reindex(@PathVariable String id) throws Exception { return knowledge.reindex(id); }
    @PostMapping("/seed") public Map<String,Object> seed() throws Exception { return knowledge.seed(); }
    @GetMapping("/status") public Map<String,Object> status() {
        return Map.of("dependencies",indexes.health(),"documents",repository.all().size(),
                "chunks",repository.activeChunks(null).size(),"rerankConfigured",!properties.rerankUrl().isBlank(),
                "queryOptimization",Map.of("enabled",queryProperties.enabled(),"configured",planner.configured(),
                        "maxQueries",queryProperties.maxQueries()));
    }
}
