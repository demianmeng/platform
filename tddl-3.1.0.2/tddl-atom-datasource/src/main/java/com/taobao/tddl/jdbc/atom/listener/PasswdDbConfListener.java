package com.taobao.tddl.jdbc.atom.listener;

/**数据库密码变化监听器
 * 
 * @author qihao
 *
 */
public interface PasswdDbConfListener {

	void handleData(String dataId, String data);
}
