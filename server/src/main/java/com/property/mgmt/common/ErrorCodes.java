package com.property.mgmt.common;

public final class ErrorCodes {
    private ErrorCodes() {}
    public static final int BAD_PARAM = 40001;
    public static final int MOBILE_TAKEN = 40002;
    public static final int OWNER_EXISTS = 40003;
    public static final int ID_CARD_TAKEN = 40004;
    public static final int OCCUPANT_LIMIT = 40005;
    public static final int MANAGER_EXISTS = 40006;
    public static final int NEED_MANAGER_FIRST = 40007;
    public static final int STAFF_OR_COMMITTEE_DUP = 40008;
    public static final int DIRECTOR_EXISTS = 40009;
    public static final int PLATE_DUP = 40010;
    public static final int SPACE_DUP = 40011;
    public static final int ATTACHMENT_LIMIT = 40012;
    public static final int AUTH_IDENTITY_FORBIDDEN = 40013;
    public static final int GUEST_FORBIDDEN = 40014;
    public static final int BILL_GEN_FAIL = 40015;
    public static final int BILL_LOCKED = 40016;
    public static final int BILL_PAID_NO_REPUBLISH = 40017;
    public static final int NO_COMMITTEE = 40018;
    public static final int VOTE_CLOSED = 40019;
    /** 不可移除小区唯一物业经理 */
    public static final int LAST_MANAGER = 40020;
    /** 账单支付处理中，禁止覆盖 */
    public static final int BILL_PAYING = 40021;
    public static final int UNAUTHORIZED = 40101;
    public static final int FORBIDDEN = 40301;
    /** 须先修改临时密码后再使用物业后台 */
    public static final int MUST_CHANGE_PASSWORD = 40302;
    public static final int NOT_FOUND = 40401;
    public static final int SERVER = 50001;
}
