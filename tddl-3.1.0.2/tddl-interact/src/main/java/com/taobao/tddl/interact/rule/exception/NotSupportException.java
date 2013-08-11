package com.taobao.tddl.interact.rule.exception;

public class NotSupportException extends TDLRunTimeException{
	/**
	 * serialVersionUID
	 */
	private static final long serialVersionUID = 1130122397745964828L;

	public NotSupportException(String msg) {
		super("not support yet."+msg);
		
	}
}
