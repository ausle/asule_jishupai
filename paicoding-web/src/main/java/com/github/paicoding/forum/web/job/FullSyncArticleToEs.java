package com.github.paicoding.forum.web.job;

import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章全量同步到ES
 */
@Component
@Slf4j
public class FullSyncArticleToEs implements CommandLineRunner {

    private static final int BULK_SIZE = 500;

    private final ArticleDao articleDao;

    private final ObjectProvider<RestHighLevelClient> restHighLevelClientProvider;

    @Value("${elasticsearch.article-index:asule_article_v2}")
    private String articleIndex;

    public FullSyncArticleToEs(ArticleDao articleDao,
                               ObjectProvider<RestHighLevelClient> restHighLevelClientProvider) {
        this.articleDao = articleDao;
        this.restHighLevelClientProvider = restHighLevelClientProvider;
    }

    /*

        SELECT a.id,ad.version,a.summary,ad.content from article a left join article_detail ad ON a.id = ad.article_id;

        -- 下面这条等价于内连接：SELECT a.id, ad.version, a.summary, ad.content FROM article a INNER JOIN article_detail ad ON a.id = ad.article_id;
        SELECT a.id,ad.version,a.summary,ad.content from article a,article_detail ad where a.id = ad.article_id;
     */
    @Override
    public void run(String... args) {
        RestHighLevelClient client = restHighLevelClientProvider.getIfAvailable();
        if (client == null) {
            log.info("skip full article sync: Elasticsearch client is not available");
            return;
        }

        List<ArticleSearchDocumentDTO> documents = articleDao.listAllArticleSearchDocuments();
        if (documents == null || documents.isEmpty()) {
            log.info("skip full article sync: no article data found");
            return;
        }

        try {
            int synced = 0;
            for (int from = 0; from < documents.size(); from += BULK_SIZE) {
                int to = Math.min(from + BULK_SIZE, documents.size());
                List<ArticleSearchDocumentDTO> batch = documents.subList(from, to);
                BulkResponse response = bulkIndex(client, batch);
                if (response != null && response.hasFailures()) {
                    throw new IllegalStateException("Elasticsearch bulk sync failed: "
                            + response.buildFailureMessage());
                }
                synced += batch.size();
                log.info("full sync article batch to Elasticsearch: index={}, synced={}/{}",
                        articleIndex, synced, documents.size());
            }
            client.indices().refresh(new RefreshRequest(articleIndex), RequestOptions.DEFAULT);
            log.info("full sync articles to Elasticsearch success: index={}, count={}",
                    articleIndex, synced);
        } catch (Exception e) {
            log.error("full sync articles to Elasticsearch failed: index={}", articleIndex, e);
        }
    }

    private BulkResponse bulkIndex(RestHighLevelClient client,
                                   List<ArticleSearchDocumentDTO> documents) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        for (ArticleSearchDocumentDTO document : documents) {
            if (document == null || document.getArticleId() == null) {
                continue;
            }
            bulkRequest.add(new IndexRequest(articleIndex)
                    .id(String.valueOf(document.getArticleId()))
                    .source(toEsDocument(document), XContentType.JSON));
        }
        if (bulkRequest.numberOfActions() == 0) {
            return null;
        }
        return client.bulk(bulkRequest, RequestOptions.DEFAULT);
    }

    private Map<String, Object> toEsDocument(ArticleSearchDocumentDTO document) {
        // exact适用于精确匹配。
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("articleId", document.getArticleId());
        source.put("authorId", document.getAuthorId());
        source.put("authorName", document.getAuthorName());
        source.put("title", document.getTitle());
        source.put("titleExact", document.getTitle());
        source.put("shortTitle", document.getShortTitle());
        source.put("shortTitleExact", document.getShortTitle());
        source.put("urlSlug", document.getUrlSlug());
        source.put("summary", document.getSummary());
        source.put("summaryExact", document.getSummary());
        source.put("content", StringUtils.defaultString(document.getContent()));
        source.put("contentExact", StringUtils.defaultString(document.getContent()));
        source.put("status", document.getStatus());
        source.put("officalStat", document.getOfficalStat());
        source.put("toppingStat", document.getToppingStat());
        source.put("deleted", document.getDeleted());
        source.put("columnIds", document.parseColumnIds());
        source.put("updateTime", document.getUpdateTime());
        return source;
    }
}
