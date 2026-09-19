package com.github.paicoding.forum.web.front.test.rest;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSONObject;
import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.enums.pay.ThirdPayWayEnum;
import com.github.paicoding.forum.api.model.exception.ForumAdviceException;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.ResVo;
import com.github.paicoding.forum.api.model.vo.Status;
import com.github.paicoding.forum.api.model.vo.constants.StatusEnum;
import com.github.paicoding.forum.core.autoconf.DynamicConfigContainer;
import com.github.paicoding.forum.core.dal.DsAno;
import com.github.paicoding.forum.core.dal.DsSelectExecutor;
import com.github.paicoding.forum.core.dal.MasterSlaveDsEnum;
import com.github.paicoding.forum.core.permission.Permission;
import com.github.paicoding.forum.core.permission.UserRole;
import com.github.paicoding.forum.core.senstive.SensitiveService;
import com.github.paicoding.forum.core.util.EmailUtil;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.core.util.MapUtils;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.article.conveter.PayConverter;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.config.service.GlobalConfigService;
import com.github.paicoding.forum.service.pay.model.PrePayInfoResBo;
import com.github.paicoding.forum.service.pay.model.ThirdPayOrderReqBo;
import com.github.paicoding.forum.service.pay.service.ThirdPayHandler;
import com.github.paicoding.forum.service.statistics.converter.StatisticsConverter;
import com.github.paicoding.forum.service.statistics.repository.entity.RequestCountDO;
import com.github.paicoding.forum.service.statistics.repository.entity.RequestCountExcelDO;
import com.github.paicoding.forum.service.statistics.service.RequestCountService;
import com.github.paicoding.forum.service.statistics.service.StatisticsSettingService;
import com.github.paicoding.forum.service.statistics.service.impl.CountServiceImpl;
import com.github.paicoding.forum.web.front.login.wx.helper.WxLoginQrGenIntegration;
import com.github.paicoding.forum.web.front.test.vo.EmailReqVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.util.ProxyUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用于一些功能测试的入口，默认都使用从库，不支持修改数据
 *
 * @author YiHui
 * @date 2023/3/19
 */
@Slf4j
@DsAno(MasterSlaveDsEnum.SLAVE)
@RestController
@RequestMapping(path = "/test/asule")
public class TestAsuleController {

    @RequestMapping(path = "Cacheable")
    /*
        cacheNames表示的是：缓存区域
        key是缓存KEY。

        其中key、condition中可支持EL表达式。
        condition，设置的条件达成时，才写入缓存。

        当然方法发生异常时，缓存不会保存成功。
    */
    @Cacheable(cacheNames = "say", key = "'p_'+ #name",condition = "#age % 2 ==0")
    public String sayHello1(String name,int age) {
        return "hello+" + name + "-->" + UUID.randomUUID().toString();
    }

    @RequestMapping(path = "CachePut")
    /*
        不管有没有缓存，都把结果写到缓存。
        适用于更新缓存场景。
    */
    @CachePut(cacheNames = "say", key = "'p_'+ #name")
    public String sayHello2(String name,int age) {
        return "hello+" + name + "-->" + UUID.randomUUID().toString();
    }


    @RequestMapping(path = "CacheEvict")
    /*
        删除缓存，删除指定KEY对应的缓存。
    */
    @CacheEvict(cacheNames = "say", key = "'p_'+ #name")
    public String sayHello3(String name) {
        return "hello+" + name + "-->" + UUID.randomUUID().toString();
    }


    /*
        适用于复杂的场景，既能保存到缓存，又可以删掉某些缓存。
        会把方法返回的结果写到caching_#age中，同时删掉t4_#age对应的缓存。
     */
    @Caching(cacheable = @Cacheable(cacheNames = "caching", key = "#age"), evict = @CacheEvict(cacheNames = "t4", key = "#age"))
    public String caching(int age) {
        return "caching: " + age + "-->" + UUID.randomUUID().toString();
    }
}
