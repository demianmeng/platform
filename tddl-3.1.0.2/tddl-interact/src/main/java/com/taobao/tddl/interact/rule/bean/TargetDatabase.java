//Copyright(c) Taobao.com
package com.taobao.tddl.interact.rule.bean;

import java.util.List;

/**
 * @description
 * @author <a href="junyu@taobao.com">junyu</a> 
 * @version 1.0
 * @since 1.6
 * @date 2011-3-29下午02:22:04
 */
public class TargetDatabase {
	//目标dbKey
	private String dbIndex;
	//所要执行的实际表
	private List<String> tableNames;
	public String getDbIndex() {
		return dbIndex;
	}
	public void setDbIndex(String dbIndex) {
		this.dbIndex = dbIndex;
	}
	public List<String> getTableNames() {
		return tableNames;
	}
	public void setTableNames(List<String> tableNames) {
		this.tableNames = tableNames;
	}
}
