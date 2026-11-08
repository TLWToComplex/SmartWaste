package com.smartwaste.rag;

import com.fasterxml.jackson.databind.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.util.*;

/** Elasticsearch BM25 与 Chroma v2；两个召回器共享稳定分块 ID。 */
@Component
public class SearchIndexes {
    public record Hit(String id, double score) { }
    public record Recall(List<List<Hit>> rankings, int failedQueries) { }
    private final RagProperties properties;
    private final ObjectMapper json;
    private final EmbeddingModel embeddings;
    private final RestClient es, chroma;
    private volatile String collection;

    public SearchIndexes(RagProperties properties, ObjectMapper json, EmbeddingModel embeddings,
                         RestClient.Builder builder) {
        this.properties=properties; this.json=json; this.embeddings=embeddings;
        if (!properties.esIndex().matches("[a-z0-9][a-z0-9_-]{2,100}")) throw new IllegalArgumentException("ES 索引名称不合法");
        var esBuilder=builder.clone().baseUrl(properties.esUrl());
        if (!properties.esApiKey().isBlank()) esBuilder.defaultHeader("Authorization","ApiKey "+properties.esApiKey());
        es=esBuilder.build();
        var chromaBuilder=builder.clone().baseUrl(properties.chromaUrl());
        if(!properties.chromaToken().isBlank()) chromaBuilder.defaultHeader("x-chroma-token",properties.chromaToken());
        chroma=chromaBuilder.build();
    }

    private String index() { return "/"+properties.esIndex(); }
    private String collections() {
        return "/api/v2/tenants/"+segment(properties.chromaTenant())+"/databases/"+segment(properties.chromaDatabase())+"/collections";
    }
    private String segment(String value) {
        if (!value.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Chroma 路径参数不合法");
        return value;
    }
    private JsonNode post(RestClient client,String path,Object body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }
    private synchronized String collection() {
        if(collection==null) {
            var response=post(chroma,collections(),Map.of("name",properties.chromaCollection(),"get_or_create",true,
                    "configuration",Map.of("hnsw",Map.of("space","cosine"))));
            collection=response.path("id").asText();
            if(collection.isBlank()) { collection=null; throw new IllegalStateException("Chroma 未返回集合 ID"); }
        }
        return collections()+"/"+collection;
    }
    private void ensureElasticsearch() {
        try {
            es.get().uri(index()).retrieve().toBodilessEntity();
        } catch(HttpClientErrorException.NotFound ex) {
            // cjk 为内置分析器，无需安装 IK；中文双字词适合分类及设备说明检索。
            Map<String,Object> text=Map.of("type","text","analyzer","cjk");
            var mapping=Map.of("mappings",Map.of("properties",Map.of(
                    "content",text,"title",text,"section",text,
                    "documentId",Map.of("type","keyword"),"revision",Map.of("type","keyword"),
                    "category",Map.of("type","keyword"),"source",Map.of("type","keyword"))));
            try { es.put().uri(index()).contentType(MediaType.APPLICATION_JSON).body(mapping).retrieve().toBodilessEntity(); }
            catch(HttpClientErrorException.BadRequest race) {
                if(!race.getResponseBodyAsString().contains("resource_already_exists_exception")) throw race;
            }
        }
    }

    public void upsert(List<Knowledge.Chunk> chunks) throws Exception {
        ensureElasticsearch();
        String chromaPath=collection();
        for(int start=0;start<chunks.size();start+=32) {
            var batch=chunks.subList(start,Math.min(start+32,chunks.size()));
            List<String> texts=batch.stream().map(c->c.title()+"\n"+c.section()+"\n"+c.content()).toList();
            var vectors=embeddings.embed(texts);
            if(vectors.size()!=batch.size()) throw new IllegalStateException("向量服务返回数量不匹配");
            StringBuilder bulk=new StringBuilder();
            for(var chunk:batch) {
                bulk.append(json.writeValueAsString(Map.of("index",Map.of("_id",chunk.id())))).append('\n');
                bulk.append(json.writeValueAsString(chunk)).append('\n');
            }
            var result=es.post().uri(index()+"/_bulk?refresh=wait_for").contentType(MediaType.parseMediaType("application/x-ndjson"))
                    .body(bulk.toString()).retrieve().body(JsonNode.class);
            if(result==null||result.path("errors").asBoolean(true)) throw new IllegalStateException("Elasticsearch 分块写入失败");
            post(chroma,chromaPath+"/upsert",Map.of("ids",batch.stream().map(Knowledge.Chunk::id).toList(),
                    "embeddings",vectors,"documents",texts,"metadatas",batch.stream().map(c->Map.of(
                            "documentId",c.documentId(),"revision",c.revision(),"category",c.category())).toList()));
        }
    }

    public void delete(List<Knowledge.Chunk> chunks) {
        if(chunks.isEmpty()) return;
        for(int start=0; start<chunks.size(); start+=100) {
            var ids=chunks.subList(start,Math.min(start+100,chunks.size())).stream().map(Knowledge.Chunk::id).toList();
            post(es,index()+"/_delete_by_query?refresh=true",Map.of("query",Map.of("ids",Map.of("values",ids))));
            post(chroma,collection()+"/delete",Map.of("ids",ids));
        }
    }

    private Object allowedFilter(Set<String> allowed) {
        return Map.of("ids",Map.of("values",allowed));
    }
    public Recall keyword(List<String> questions,Set<String> allowed,int topK) throws Exception {
        if(allowed.isEmpty()||questions.isEmpty()) return new Recall(List.of(),0);
        StringBuilder bulk=new StringBuilder();
        for(String question:questions) {
            var body=Map.of("size",topK,"_source",false,"query",Map.of("bool",Map.of(
                    "must",List.of(Map.of("multi_match",Map.of("query",question,"fields",List.of("title^3","section^2","content"),
                            "minimum_should_match","35%"))),"filter",List.of(allowedFilter(allowed)))));
            bulk.append("{}\n").append(json.writeValueAsString(body)).append('\n');
        }
        var response=es.post().uri(index()+"/_msearch").contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(bulk.toString()).retrieve().body(JsonNode.class);
        if(response==null||!response.path("responses").isArray()||response.path("responses").size()!=questions.size())
            throw new IllegalStateException("关键词多查询返回数量不匹配");
        List<List<Hit>> rankings=new ArrayList<>(); int failed=0;
        for(var item:response.path("responses")) {
            List<Hit> hits=new ArrayList<>();
            if(item.has("error")||!item.path("hits").path("hits").isArray()) failed++;
            else for(var hit:item.path("hits").path("hits")) {
                String id=hit.path("_id").asText();
                if(allowed.contains(id)) hits.add(new Hit(id,hit.path("_score").asDouble()));
            }
            rankings.add(List.copyOf(hits));
        }
        return new Recall(List.copyOf(rankings),failed);
    }
    public Recall vector(List<String> questions,Map<String,Knowledge.Chunk> allowed,int topK) {
        if(allowed.isEmpty()||questions.isEmpty()) return new Recall(List.of(),0);
        var revisions=allowed.values().stream().map(Knowledge.Chunk::revision).distinct().toList();
        // 所有查询一次向量化、一次 Chroma 查询，避免扩展为十次独立 HTTP 召回。
        var vectors=embeddings.embed(questions);
        if(vectors.size()!=questions.size()) throw new IllegalStateException("查询向量数量不匹配");
        var body=Map.of("query_embeddings",vectors,"n_results",Math.min(topK,allowed.size()),
                "where",Map.of("revision",Map.of("$in",revisions)),"include",List.of("distances"));
        var response=post(chroma,collection()+"/query",body);
        if(response==null||!response.path("ids").isArray()||response.path("ids").size()!=questions.size())
            throw new IllegalStateException("向量多查询返回数量不匹配");
        List<List<Hit>> rankings=new ArrayList<>(); int failed=0;
        for(int row=0;row<questions.size();row++) {
            var ids=response.path("ids").path(row);
            var distances=response.path("distances").path(row);
            List<Hit> hits=new ArrayList<>();
            if(!ids.isArray()||!distances.isArray()||ids.size()!=distances.size()) failed++;
            else for(int i=0;i<ids.size();i++) {
                double distance=distances.path(i).asDouble(Double.POSITIVE_INFINITY);
                if(allowed.containsKey(ids.get(i).asText())&&Double.isFinite(distance)&&distance<=properties.vectorMaxDistance())
                    hits.add(new Hit(ids.get(i).asText(),1-distance));
            }
            rankings.add(List.copyOf(hits));
        }
        return new Recall(List.copyOf(rankings),failed);
    }

    public Map<String,Boolean> health() {
        Map<String,Boolean> result=new LinkedHashMap<>();
        try { es.get().uri("/_cluster/health").retrieve().toBodilessEntity(); result.put("elasticsearch",true); }
        catch(Exception ex) { result.put("elasticsearch",false); }
        try { chroma.get().uri("/api/v2/heartbeat").retrieve().toBodilessEntity(); result.put("chroma",true); }
        catch(Exception ex) { result.put("chroma",false); }
        return result;
    }
}
