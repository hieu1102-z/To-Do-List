package com.finalexam.to_do_list.model.repository

/** Safe messages: never surface a raw Firebase response, password, or token. */
class AuthFailure private constructor(val code: String, message: String) : Exception(message) {
    companion object {
        val INVALID_EMAIL = AuthFailure("INVALID_EMAIL", "Nhập địa chỉ email hợp lệ.")
        val PASSWORD_REQUIRED = AuthFailure("PASSWORD_REQUIRED", "Vui lòng nhập mật khẩu.")
        val INVALID_CREDENTIALS = AuthFailure("INVALID_CREDENTIALS", "Email hoặc mật khẩu không chính xác.")
        val USER_DISABLED = AuthFailure("USER_DISABLED", "Tài khoản đã bị vô hiệu hóa.")
        val NETWORK = AuthFailure("NETWORK", "Không có kết nối mạng. Hãy kiểm tra mạng hoặc tiếp tục dùng offline.")
        val TOO_MANY_REQUESTS = AuthFailure("TOO_MANY_REQUESTS", "Có quá nhiều yêu cầu. Vui lòng thử lại sau.")
        val NOT_CONFIGURED = AuthFailure("NOT_CONFIGURED", "Chức năng tài khoản chưa sẵn sàng. Bạn vẫn có thể tiếp tục dùng offline.")
        val PROVIDER_DISABLED = AuthFailure("PROVIDER_DISABLED", "Đăng nhập email chưa được bật. Vui lòng liên hệ quản trị viên.")
        val LOCAL_STORAGE = AuthFailure("LOCAL_STORAGE", "Không thể lưu hồ sơ đăng nhập trên thiết bị. Vui lòng thử lại.")
        val CANCELLED = AuthFailure("CANCELLED", "Yêu cầu đăng nhập đã bị hủy.")
        val BUSY = AuthFailure("BUSY", "Yêu cầu đăng nhập trước đang được xử lý. Vui lòng thử lại sau.")
        val UNKNOWN = AuthFailure("UNKNOWN", "Không thể xử lý yêu cầu lúc này. Vui lòng thử lại.")
        val NAME_REQUIRED = AuthFailure("NAME_REQUIRED", "Vui lòng nhập họ tên.")
        val WEAK_PASSWORD = AuthFailure("WEAK_PASSWORD", "Mật khẩu cần ít nhất 6 ký tự và đáp ứng chính sách bảo mật của Firebase.")
        val PASSWORD_MISMATCH = AuthFailure("PASSWORD_MISMATCH", "Mật khẩu xác nhận không khớp.")
        val EMAIL_IN_USE = AuthFailure("EMAIL_IN_USE", "Email này đã có tài khoản. Hãy đăng nhập hoặc chọn Quên mật khẩu.")
        val SESSION_REQUIRED = AuthFailure("SESSION_REQUIRED", "Vui lòng đăng nhập lại để xác minh email.")
        val VERIFICATION_COOLDOWN = AuthFailure("VERIFICATION_COOLDOWN", "Vui lòng chờ 60 giây giữa các lần gửi email xác minh.")
        val REGISTRATION_LOCAL_STORAGE = AuthFailure("REGISTRATION_LOCAL_STORAGE", "Tài khoản đã được tạo nhưng chưa lưu được hồ sơ trên thiết bị. Hãy quay lại đăng nhập, không cần đăng ký lại.")
    }
}
