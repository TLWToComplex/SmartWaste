package com.smartwaste.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;

@Service
public class HybridRetriever {
    public record Match(Knowledge.Chunk chunk, double fusionScore, Double rerankScore) { }
    public record Result(List<Match> matches,List<String> warnings,boolean reranked) { }
    private final SearchIndexes indexes;
    private final KnowledgeRepository repository;
    private final RagProperties properties;
    private final RetrievalProperties retrieval;
    private final RestClient reranker;
    public HybridRetriever(SearchIndexes indexes,KnowledgeRepository repository,RagProperties properties,
                           RetrievalProperties retrieval,RestClient.Builder builder) {
        this.indexes=indexes; this.repository=repository; this.properties=properties;
        this.retrieval=retrieval;
        var client=builder.clone();
        if(!properties.rerankKey().isBlank()) client.defaultHeader("Authorization","Bearer "+properties.rerankKey());
        reranker=client.build();
    }
    public Result search(QueryPlanner.Plan plan,String category) {
        var active=repository.activeChunks(category);
        List<String> warnings=new ArrayList<>();
        if(active.isEmpty()) return new Result(List.of(),warnings,false);
        List<List<SearchIndexes.Hit>> keyword=List.of(),vector=List.of();
        try {
            var recall=indexes.keyword(plan.queries(),active.keySet(),retrieval.perQueryTopK());
            keyword=recall.rankings();
            if(recall.failedQueries()>0) warnings.add("部分关键词查询未完成，已使用可用召回结果");
        }
        catch(Exception ex) { warnings.add("关键词检索暂不可用"); }
        try {
            var recall=indexes.vector(plan.queries(),active,retrieval.perQueryTopK());
            vector=recall.rankings();
            if(recall.failedQueries()>0) warnings.add("部分向量查询未完成，已使用可用召回结果");
        }
        catch(Exception ex) { warnings.add("向量检索暂不可用"); }
        // 加权 RRF：原问题权重 1，其余查询的权重总和为 1。
        // 扩展数增加不会线性放大扩展查询对排名的影响；文档按分块 ID 去重。
        Map<String,Double> scores=new HashMap<>();
        for(var branch:List.of(keyword,vector)) {
            for(int query=0;query<branch.size();query++) {
                double weight=query==0?1.0:1.0/Math.max(1,plan.queries().size()-1);
                var ranking=branch.get(query);
                Set<String> seen=new HashSet<>();
                for(int i=0;i<ranking.size();i++) {
                    String id=ranking.get(i).id();
                    if(active.containsKey(id)&&seen.add(id)) scores.merge(id,weight/(60+i+1),Double::sum);
                }
            }
        }
        var candidates=scores.entrySet().stream()
                .sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(retrieval.candidateLimit()).map(e->new Match(active.get(e.getKey()),e.getValue(),null)).toList();
        if(candidates.isEmpty()) return new Result(List.of(),warnings,false);
        if(properties.rerankUrl().isBlank()) {
            warnings.add("未配置精排服务，当前使用融合检索结果");
            return new Result(candidates.stream().limit(retrieval.evidenceLimit()).toList(),warnings,false);
        }
        try {
            var result=reranker.post().uri(properties.rerankUrl()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model",properties.rerankModel(),"query",plan.primaryQuery(),"top_n",Math.min(retrieval.evidenceLimit(),candidates.size()),
                            "documents",candidates.stream().map(m->m.chunk().title()+"\n"+m.chunk().section()+"\n"+m.chunk().content()).toList()))
                    .retrieve().body(JsonNode.class);
            if(result==null||!result.path("results").isArray()) throw new IllegalStateException("精排响应缺少 results");
            List<Match> ranked=new ArrayList<>();
            Set<Integer> seen=new HashSet<>();
            for(var item:result.path("results")) {
                int position=item.path("index").asInt(-1);
                double score=item.path("relevance_score").asDouble(Double.NaN);
                if(position<0||position>=candidates.size()||!Double.isFinite(score)||!seen.add(position))
                    throw new IllegalStateException("精排结果索引或分数无效");
                if(score>=properties.rerankMinScore()) {
                    var candidate=candidates.get(position);
                    ranked.add(new Match(candidate.chunk(),candidate.fusionScore(),score));
                }
            }
            ranked.sort(Comparator.comparing(Match::rerankScore).reversed());
            return new Result(ranked.stream().limit(retrieval.evidenceLimit()).toList(),warnings,true);
        } catch(Exception ex) {
            warnings.add("精排服务暂不可用，当前使用融合检索结果");
            return new Result(candidates.stream().limit(retrieval.evidenceLimit()).toList(),warnings,false);
        }
    }
}
