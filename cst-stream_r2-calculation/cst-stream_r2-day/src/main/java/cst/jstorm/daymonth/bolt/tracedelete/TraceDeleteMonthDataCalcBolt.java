package cst.jstorm.daymonth.bolt.tracedelete;

import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseBasicBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import com.cst.stream.common.*;
import com.cst.stream.stathour.tracedelete.TraceDeleteDayTransfor;
import com.cst.stream.stathour.tracedelete.TraceDeleteMonthTransfor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.cst.jstorm.commons.stream.constants.OtherKey;
import com.cst.jstorm.commons.stream.constants.PropKey;
import com.cst.jstorm.commons.stream.constants.RedisKey;
import com.cst.jstorm.commons.stream.constants.StreamKey;
import com.cst.jstorm.commons.stream.custom.CustomContextConfiguration;
import com.cst.jstorm.commons.stream.operations.GeneralAccumulationStreamExecution;
import com.cst.jstorm.commons.stream.operations.hbasestrategy.IHBaseQueryAndPersistStrategy;
import com.cst.jstorm.commons.stream.operations.hbasestrategy.StrategyChoose;
import com.cst.jstorm.commons.utils.HttpURIUtil;
import com.cst.jstorm.commons.utils.LogbackInitUtil;
import com.cst.jstorm.commons.utils.PropertiesUtil;
import com.cst.jstorm.commons.utils.RedisUtil;
import com.cst.jstorm.commons.utils.exceptions.NoSourceDataException;
import com.cst.jstorm.commons.utils.http.HttpUtils;
import com.cst.jstorm.commons.utils.spring.MyApplicationContext;
import cst.jstorm.daymonth.calcalations.tracedelete.TraceDeleteMonthCalcBiz;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import redis.clients.jedis.JedisCluster;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;

/**
 * @author Johnney.chiu
 * create on 2017/12/18 16:16
 * @Description trace delete数据的月计算
 */
public class TraceDeleteMonthDataCalcBolt extends BaseBasicBolt {

    private static final long serialVersionUID = -4316113077642283344L;
    private transient Logger logger;
    private transient JedisCluster jedis;
    private AbstractApplicationContext beanContext;
    private Properties prop;
    private boolean forceLoad;
    private transient org.apache.hadoop.hbase.client.Connection connection;
    private transient HttpUtils httpUtils;
    private TraceDeleteMonthCalcBiz traceDeleteMonthCalcBiz;

    public TraceDeleteMonthDataCalcBolt(Properties prop, boolean forceLoad) {
        this.prop = prop;
        this.forceLoad = forceLoad;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context) {
        super.prepare(stormConf, context);
        prop = PropertiesUtil.initProp(prop, forceLoad);
        LogbackInitUtil.changeLogback(prop, true);
        jedis = RedisUtil.buildJedisCluster(prop, RedisKey.STORM_REDISCLUSTER);
        beanContext = MyApplicationContext.getDefaultContext();
        logger = LoggerFactory.getLogger(TraceDeleteMonthDataCalcBolt.class);

        connection = (org.apache.hadoop.hbase.client.Connection) beanContext.getBean(OtherKey.DataDealKey.HBASE_CONNECTION);
        httpUtils = (HttpUtils) beanContext.getBean(OtherKey.DataDealKey.HTTP_UTILS);
        traceDeleteMonthCalcBiz = new TraceDeleteMonthCalcBiz();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(Tuple input, BasicOutputCollector collector) {
        if (!StreamKey.TraceDeleteStream.TRACE_DELETE_MONTH_BOLT_F.equals(input.getSourceStreamId()))
            return;
        String msg = input.getString(0);
        if(StringUtils.isEmpty(msg))
            return;

        try {
            logger.debug("trace delete day transfor data:{}", msg);
            Map map = new HashMap<String, Object>() {{
                put(OtherKey.DataDealKey.TIME_SELECT, CstConstants.TIME_SELECT.MONTH);
                put(OtherKey.MIDLLE_DEAL.NEXT_KEY, new ArrayList<String>());
                put(OtherKey.MIDLLE_DEAL.TIME_FORMAT, DateTimeUtils.TimeFormat.LONG_DATE_PATTERN_LINE);
                put(OtherKey.MIDLLE_DEAL.ZONE_VALUE_EXPIRE_TIME, prop.getProperty("month.zone.value.expire.time"));
            }};
            GeneralAccumulationStreamExecution<TraceDeleteDayTransfor, TraceDeleteMonthTransfor, TraceDeleteMonthCalcBiz> generalStreamExecution = new GeneralAccumulationStreamExecution<>()
                    .createJedis(jedis)
                    .createSpecialCalc(traceDeleteMonthCalcBiz)
                    .createSpecialSource(msg, StreamRedisConstants.MonthKey.MONTH_TRACE_DELETE, DateTimeUtil.DEFAULT_DATE_MONTH);
            //如果从缓存中拿到不是空数据
            IHBaseQueryAndPersistStrategy<TraceDeleteMonthTransfor> iResultStrategy = StrategyChoose.generateStrategy(prop.getProperty(PropKey.DEAL_STRATEGY),
                    httpUtils, prop.getProperty("url_base"), HttpURIUtil.TRACE_DELETE_MONTH_FIND,
                    connection, HBaseTable.MONTH_STATISTICS.getTableName(),
                    HBaseTable.MONTH_STATISTICS.getSixthFamilyName(), HbaseColumn.MonthStatisticsCloumn.traceDeleteMonthColumns,
                    TraceDeleteMonthTransfor.class);
            generalStreamExecution.dealAccumulationData(map, iResultStrategy);
            //hbase中拿不到该时区数据 查找最近一条上传的数据
            List<String> persistValues = (List) map.get(OtherKey.MIDLLE_DEAL.PERSIST_KEY);
            if(CollectionUtils.isNotEmpty(persistValues))
                for (String str : persistValues)
                    collector.emit(StreamKey.TraceDeleteStream.TRACE_DELETE_MONTH_BOLT_S, new Values(str, generalStreamExecution.gentMsgId()));


        } catch (NoSourceDataException e) {
            logger.error("trace delete data execute,no source  data is{}:", msg, e);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            logger.error("trace delete data execute data is{}:", msg, e);
        } catch (ParseException e) {
            logger.error("trace delete data execute data is{}:", msg, e);
        } catch (IOException e) {
            logger.error("trace delete data execute data is{}:", msg, e);
        } catch (NullPointerException e) {
            logger.error("trace delete data execute  data is{}:", msg, e);
        } catch (Exception e) {
            logger.error("trace delete data execute  data is{}:", msg, e);
        }

    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream(StreamKey.TraceDeleteStream.TRACE_DELETE_MONTH_BOLT_S, new Fields(new String[] {
                StreamKey.TraceDeleteStream.TRACE_DELETE_KEY_F, StreamKey.TraceDeleteStream.TRACE_DELETE_KEY_S}));
    }
    @Override
    public void cleanup() {
        super.cleanup();
        beanContext.close();
    }

}
