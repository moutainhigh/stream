package cst.jstorm.daymonth.bolt.mileage;

import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseBasicBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import com.cst.jstorm.commons.stream.constants.OtherKey;
import com.cst.jstorm.commons.stream.constants.PropKey;
import com.cst.jstorm.commons.stream.constants.RedisKey;
import com.cst.jstorm.commons.stream.constants.StreamKey;
import com.cst.jstorm.commons.stream.custom.ComsumerContextSelect;
import com.cst.jstorm.commons.stream.custom.GasProcess;
import com.cst.jstorm.commons.stream.operations.GeneralDataStreamExecution;
import com.cst.jstorm.commons.stream.operations.hbasestrategy.IHBaseQueryAndPersistStrategy;
import com.cst.jstorm.commons.stream.operations.hbasestrategy.StrategyChoose;
import com.cst.jstorm.commons.utils.HttpURIUtil;
import com.cst.jstorm.commons.utils.LogbackInitUtil;
import com.cst.jstorm.commons.utils.PropertiesUtil;
import com.cst.jstorm.commons.utils.RedisUtil;
import com.cst.jstorm.commons.utils.exceptions.NoSourceDataException;
import com.cst.jstorm.commons.utils.http.HttpUtils;
import com.cst.stream.common.*;
import com.cst.stream.stathour.mileage.MileageDayLatestData;
import com.cst.stream.stathour.mileage.MileageDayTransfor;
import com.cst.stream.stathour.mileage.MileageHourSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import cst.jstorm.daymonth.calcalations.mileage.MileageDayCalcBiz;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
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
 * @Description mileage数据的天机算
 */
public class MileageDayDataCalcBolt extends BaseBasicBolt {

    private static final int EXTIRE_TIME = 2 * 24 * 60 * 60;
    private static final long serialVersionUID = -4316113077642283344L;
    private transient Logger logger;
    private transient JedisCluster jedis;
    private AbstractApplicationContext beanContext;
    private Properties prop;
    private boolean forceLoad;
    private transient org.apache.hadoop.hbase.client.Connection connection;
    private transient HttpUtils httpUtils;
    /** 车辆油单价缓存秒数 */
    private int CAR_GAS_PRICE_EXPIRE_SECONDS;
    private MileageDayCalcBiz mileageDayCalcBiz;

    public MileageDayDataCalcBolt(Properties prop, boolean forceLoad) {
        this.prop = prop;
        this.forceLoad = forceLoad;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context) {
        super.prepare(stormConf, context);
        prop = PropertiesUtil.initProp(prop, forceLoad);
        LogbackInitUtil.changeLogback(prop, true);
        jedis = RedisUtil.buildJedisCluster(prop, RedisKey.STORM_REDISCLUSTER);
        if (beanContext == null)
            beanContext = ComsumerContextSelect.getDefineContextWithHttpUtilWithParam(prop.getProperty("active.env"));
        logger = LoggerFactory.getLogger(MileageDayDataCalcBolt.class);

        //获取区域简称对应关系
        connection = (org.apache.hadoop.hbase.client.Connection) beanContext.getBean(OtherKey.DataDealKey.HBASE_CONNECTION);
        httpUtils = (HttpUtils) beanContext.getBean(OtherKey.DataDealKey.HTTP_UTILS);
        CAR_GAS_PRICE_EXPIRE_SECONDS = NumberUtils.toInt(prop.getProperty("gas.price.expire.time"), RedisKey.ExpireTime.GAS_PRICE_TIME);
        mileageDayCalcBiz = new MileageDayCalcBiz();

    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(Tuple input, BasicOutputCollector collector) {

        String msg = input.getString(0);
        if(StringUtils.isEmpty(msg))
            return;

        try {
            logger.debug("mileage hour transfor data:{}", msg);
            Map map = new HashMap<String, Object>() {{
                put(OtherKey.DataDealKey.TIME_SELECT, CstConstants.TIME_SELECT.DAY);
                put(OtherKey.MIDLLE_DEAL.NEXT_KEY, new LinkedList<String>());
                put(OtherKey.MIDLLE_DEAL.FMT, DateTimeUtil.DEFAULT_DATE_DAY);
                put(OtherKey.MIDLLE_DEAL.INTERVAL, DateTimeUtil.ONE_DAY);
                put(OtherKey.MIDLLE_DEAL.LAST_DATA_PARAM, StreamTypeDefine.MILEAGE_TYPE);
                put(OtherKey.MIDLLE_DEAL.BESINESS_KEY_TYPE,StreamTypeDefine.MILEAGE_TYPE);
                put(OtherKey.MIDLLE_DEAL.ZONE_VALUE_EXPIRE_TIME, prop.getProperty("day.zone.value.expire.time"));
                put(OtherKey.MIDLLE_DEAL.REDIS_HEAD, StreamRedisConstants.DayKey.DAY_MILEAGE);
                put(OtherKey.MIDLLE_DEAL.ZONE_SCHEDULE_EXPIRE_TIME, prop.getProperty("day.zone.schedule.expire.time"));


            }};
            GeneralDataStreamExecution<MileageHourSource, MileageDayTransfor,MileageDayLatestData, MileageDayCalcBiz> generalStreamExecution =
                    new GeneralDataStreamExecution<>()
                    .createJedis(jedis)
                    .createSpecialCalc(mileageDayCalcBiz)
                    .createSpecialSource(msg, StreamRedisConstants.DayKey.DAY_MILEAGE, DateTimeUtil.DEFAULT_DATE_DAY);

            // 获取车辆的油单价
            String fuelPerPrice= null ;
            if (!Boolean.valueOf(prop.getProperty("ignore.outside.status"))) {
                fuelPerPrice = String.valueOf(GasProcess.getCarGasPrice(
                        prop.getProperty("url_base").concat(HttpURIUtil.CAR_PRICE_URL),
                        jedis, generalStreamExecution.getS().getCarId(), generalStreamExecution.getS().getTime(),
                        DateTimeUtil.toLongTimeString(generalStreamExecution.getS().getTime(), DateTimeUtil.DEFAULT_DATE_DEFULT),
                        httpUtils,CAR_GAS_PRICE_EXPIRE_SECONDS
                ));

            }
            //计算补充
            map.put("fuelPrice", StringUtils.isBlank(fuelPerPrice)?prop.getProperty("default_oil_price"):fuelPerPrice);
            map.put("highSpeedStandard", prop.getProperty("highSpeedStandard"));
            map.put("night_high", prop.getProperty("night_high"));
            map.put("night_low", prop.getProperty("night_low"));
            map.put("travel_speed", prop.getProperty("travel_speed"));
            map.put("jump_mile", prop.getProperty("jump_mile"));
            map.put("tooling_probability_count", prop.getProperty("tooling_probability_count"));
            IHBaseQueryAndPersistStrategy<MileageHourSource> iFirstStrategy =
                    StrategyChoose.generateStrategy(prop.getProperty(PropKey.DEAL_STRATEGY),
                    httpUtils, prop.getProperty("url_base"), HttpURIUtil.MILEAGE_DAY_SOURCE_FIND,
                    connection, HBaseTable.DAY_FIRST_ZONE.getTableName(),
                    HBaseTable.DAY_FIRST_ZONE.getFirstFamilyName(), HbaseColumn.DaySourceColumn.mileageDayColumns,
                    MileageHourSource.class);
            IHBaseQueryAndPersistStrategy<MileageDayTransfor> iResultStrategy = StrategyChoose.generateStrategy(prop.getProperty(PropKey.DEAL_STRATEGY),
                    httpUtils, prop.getProperty("url_base"), HttpURIUtil.MILEAGE_DAY_FIND,
                    connection, HBaseTable.DAY_STATISTICS.getTableName(),
                    HBaseTable.DAY_STATISTICS.getEighthFamilyName(), HbaseColumn.DayStatisticsCloumn.mileageDayColumns,
                    MileageDayTransfor.class);
            generalStreamExecution.dealDayData(map,iFirstStrategy, iResultStrategy);


            List<String> persistValues = (List) map.get(OtherKey.MIDLLE_DEAL.PERSIST_KEY);
            if(CollectionUtils.isNotEmpty(persistValues))
                for (String str : persistValues)
                    collector.emit(StreamKey.MileageStream.MILEAGE_DAY_BOLT_S, new Values(str, generalStreamExecution.gentMsgId()));

            if (map.get(OtherKey.MIDLLE_DEAL.FIRST_TIME_ZONE) != null) {
                collector.emit(StreamKey.MileageStream.MILEAGE_DAY_BOLT_FIRST_DATA,
                        new Values(map.get(OtherKey.MIDLLE_DEAL.FIRST_TIME_ZONE),
                                generalStreamExecution.gentMsgId()));

            }

            List<String> nextList = (List<String>) map.get(OtherKey.MIDLLE_DEAL.NEXT_KEY);
            for (String str : nextList) {
                logger.debug("##nextValue mileage day data is {}", str);
                collector.emit(StreamKey.MileageStream.MILEAGE_MONTH_BOLT_F, new Values(str, generalStreamExecution.gentMsgId()));
                collector.emit(StreamKey.MileageStream.MILEAGE_YEAR_BOLT_F, new Values(str, generalStreamExecution.gentMsgId()));
            }

        } catch (NoSourceDataException e) {
            logger.error("mileage data execute,no source  data is{}:", msg, e);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            logger.error("mileage data execute data is{}:", msg, e);
        } catch (ParseException e) {
            logger.error("mileage data execute data is{}:", msg, e);
        } catch (IOException e) {
            logger.error("mileage data execute data is{}:", msg, e);
        } catch (NullPointerException e) {
            logger.error("mileage data execute  data is{}:", msg, e);
        } catch (Exception e) {
            logger.error("mileage data execute  data is{}:", msg, e);
        }

    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream(StreamKey.MileageStream.MILEAGE_DAY_BOLT_S, new Fields(new String[] {
                StreamKey.MileageStream.MILEAGE_KEY_F, StreamKey.MileageStream.MILEAGE_KEY_S}));
        declarer.declareStream(StreamKey.MileageStream.MILEAGE_DAY_BOLT_FIRST_DATA, new Fields(new String[] {
                StreamKey.MileageStream.MILEAGE_KEY_F, StreamKey.MileageStream.MILEAGE_KEY_S}));
        declarer.declareStream(StreamKey.MileageStream.MILEAGE_MONTH_BOLT_F, new Fields(new String[] {
                StreamKey.MileageStream.MILEAGE_KEY_F, StreamKey.MileageStream.MILEAGE_KEY_S}));
        declarer.declareStream(StreamKey.MileageStream.MILEAGE_YEAR_BOLT_F, new Fields(new String[] {
                StreamKey.MileageStream.MILEAGE_KEY_F, StreamKey.MileageStream.MILEAGE_KEY_S}));
    }
    @Override
    public void cleanup() {
        super.cleanup();
        beanContext.close();
    }

}
