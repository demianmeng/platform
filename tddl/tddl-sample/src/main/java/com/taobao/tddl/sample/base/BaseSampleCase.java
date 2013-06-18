/*(C) 2007-2012 Alibaba Group Holding Limited.	

import java.util.Date;
import org.apache.commons.lang.RandomStringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import com.taobao.tddl.sample.util.DateUtil;

/**
 * Comment for BaseSampleCase
 * <p/>
 * Author By: zhuoxue.yll
 * Created Date: 2012-2-29 ����02:29:10 
 */

public class BaseSampleCase {
	protected static final String APPNAME = "tddl_sample";
	protected static final String DBKEY_0 = "qatest_normal_0";
	protected static final String GROUP_KEY = "group_sample";
	protected static final int RANDOM_ID = Integer.valueOf(RandomStringUtils.randomNumeric(8));
	protected static String time = DateUtil.formatDate(new Date(), DateUtil.DATE_FULLHYPHEN);
	protected static String nextDay = DateUtil.getDiffDate(1, DateUtil.DATE_FULLHYPHEN);

	protected static void clearData(JdbcTemplate tddlJTX, String sql, Object[] args) {
		if (args == null) {
			args = new Object[] {};
		}
		// ȷ����������ɹ�
		try {
			tddlJTX.update(sql, args);
		} catch (Exception e) {
			tddlJTX.update(sql, args);
		}
	}

	protected static void prepareData(JdbcTemplate tddlJTX, String sql, Object[] args) {
		if (args == null) {
			args = new Object[] {};
		}

		// ȷ������׼���ɹ�
		try {
			int rs = tddlJTX.update(sql, args);
			if (rs <= 0) {
				tddlJTX.update(sql, args);
			}
		} catch (Exception e) {
			int rs = tddlJTX.update(sql, args);
			if (rs <= 0) {
				tddlJTX.update(sql, args);
			}
		}
	}

}