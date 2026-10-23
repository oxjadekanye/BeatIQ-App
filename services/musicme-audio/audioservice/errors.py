"""Error codes shared with the app (AudioGenerationErrorCode)."""


class ServiceError(Exception):
    def __init__(self, status: int, code: str, message: str, retry_after: int = None):
        super().__init__(code)
        self.status = status
        self.code = code
        self.message = message
        self.retry_after = retry_after

    def body(self):
        return {"error": {"code": self.code, "message": self.message}}
