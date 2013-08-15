package com.taobao.tddl.sqlobjecttree.mysql.function.interval.datetype;

import java.util.Calendar;
import java.util.List;

import com.taobao.tddl.sqlobjecttree.common.value.Constant;

/**
 * @author junyu
 *
 */
public class IntervalMonth extends Constant{
	@Override
	public String getFuncName() {
		return " month ";
	}

	@Override
	public Comparable<?> getVal(List<Object> args) {
		return Calendar.MONTH;
	}

}
