from dataclasses import dataclass

@dataclass
class Message:
    id: int
    channel_id: int
    author_id: int
    thread_id: int
    delete_timestamp: float # time in seconds since epoch, as returned from time.time()
