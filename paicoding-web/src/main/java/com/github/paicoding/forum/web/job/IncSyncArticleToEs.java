package com.github.paicoding.forum.web.job;

import com.github.paicoding.forum.api.model.enums.YesOrNoEnum;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章增量同步到 es
 */
@Component
@Slf4j
public class IncSyncArticleToEs {

    private static final int BULK_SIZE = 500;

    private static final String TIME_ZONE = "Asia/Shanghai";

    private final ArticleDao articleDao;

    private final ObjectProvider<RestHighLevelClient> restHighLevelClientProvider;

    @Value("${elasticsearch.article-index:asule_article_v2}")
    private String articleIndex;

    public IncSyncArticleToEs(ArticleDao articleDao,
                              ObjectProvider<RestHighLevelClient> restHighLevelClientProvider) {
        this.articleDao = articleDao;
        this.restHighLevelClientProvider = restHighLevelClientProvider;
    }

    /**
     * 每 5 分钟同步前一天更新过的文章。
     *
     * <p>使用前一天的完整时间范围，避免任务刚开始时只同步到当天凌晨的部分数据。</p>
     */
//    @Scheduled(cron = "${elasticsearch.article-inc-sync-cron:0 */5 * * * ?}", zone = TIME_ZONE)
    public void run() {
        RestHighLevelClient client = restHighLevelClientProvider.getIfAvailable();
        if (client == null) {
            log.info("skip incremental article sync: Elasticsearch client is not available");
            return;
        }

        ZoneId zoneId = ZoneId.of(TIME_ZONE);
        LocalDate today = LocalDate.now(zoneId);
        Date startTime = Date.from(today.minusDays(1).atStartOfDay(zoneId).toInstant());
        Date endTime = Date.from(today.atStartOfDay(zoneId).toInstant());

        sync(client, startTime, endTime);
    }

    private void sync(RestHighLevelClient client, Date startTime, Date endTime) {
        List<ArticleSearchDocumentDTO> documents = articleDao.listArticleSearchDocumentsByUpdateTime(startTime, endTime);
        if (documents == null || documents.isEmpty()) {
            log.info("no article needs incremental synchronization: index={}, startTime={}, endTime={}",
                    articleIndex, startTime, endTime);
            return;
        }

        try {
            int synced = 0;
            for (int from = 0; from < documents.size(); from += BULK_SIZE) {
                int to = Math.min(from + BULK_SIZE, documents.size());
                List<ArticleSearchDocumentDTO> batch = documents.subList(from, to);
                BulkResponse response = bulkSync(client, batch);
                if (response != null && response.hasFailures()) {
                    throw new IllegalStateException("Elasticsearch incremental sync failed: "
                            + response.buildFailureMessage());
                }
                synced += batch.size();
                log.info("incremental sync article batch to Elasticsearch: index={}, synced={}/{}",
                        articleIndex, synced, documents.size());
            }

            client.indices().refresh(new RefreshRequest(articleIndex), RequestOptions.DEFAULT);
            log.info("incremental sync articles to Elasticsearch success: index={}, count={}, startTime={}, endTime={}",
                    articleIndex, synced, startTime, endTime);
        } catch (Exception e) {
            log.error("incremental sync articles to Elasticsearch failed: index={}, startTime={}, endTime={}",
                    articleIndex, startTime, endTime, e);
        }
    }

    private BulkResponse bulkSync(RestHighLevelClient client,
                                  List<ArticleSearchDocumentDTO> documents) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        for (ArticleSearchDocumentDTO document : documents) {
            if (document == null || document.getArticleId() == null) {
                continue;
            }

            String documentId = String.valueOf(document.getArticleId());
            if (document.getDeleted() != null && document.getDeleted() == YesOrNoEnum.YES.getCode()) {
                bulkRequest.add(new DeleteRequest(articleIndex).id(documentId));
            } else {
                bulkRequest.add(new IndexRequest(articleIndex)
                        .id(documentId)
                        .source(toEsDocument(document), XContentType.JSON));
            }
        }

        if (bulkRequest.numberOfActions() == 0) {
            return null;
        }
        return client.bulk(bulkRequest, RequestOptions.DEFAULT);
    }

    private Map<String, Object> toEsDocument(ArticleSearchDocumentDTO document) {
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
