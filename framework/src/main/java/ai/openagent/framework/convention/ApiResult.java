package ai.openagent.framework.convention;

public record ApiResult<T>(boolean success, String code, String message, T data) {

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(true, "OK", "", data);
    }

    public static <T> ApiResult<T> failure(String code, String message) {
        return new ApiResult<>(false, code, message, null);
    }
}

