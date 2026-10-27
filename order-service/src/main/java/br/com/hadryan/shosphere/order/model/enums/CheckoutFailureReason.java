package br.com.hadryan.shosphere.order.model.enums;

public enum CheckoutFailureReason {
    CART_NOT_FOUND(404), CART_CONFLICT(409), PRODUCT_CONFLICT(409), MONETARY_CONFLICT(409), ORDER_CONFLICT(409);

    private final int httpStatus;
    CheckoutFailureReason(int httpStatus) { this.httpStatus = httpStatus; }
    public int httpStatus() { return httpStatus; }
}
