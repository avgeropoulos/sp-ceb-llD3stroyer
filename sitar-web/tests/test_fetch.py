import pytest

from sitar_sitara.fetch import FetchError, video_id


@pytest.mark.parametrize(
    "url",
    [
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PL123",
        "youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtu.be/dQw4w9WgXcQ?t=10",
        "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        "https://www.youtube.com/embed/dQw4w9WgXcQ",
        "dQw4w9WgXcQ",
    ],
)
def test_video_id(url):
    assert video_id(url) == "dQw4w9WgXcQ"


@pytest.mark.parametrize(
    "url",
    [
        "https://vimeo.com/123456",
        "https://evil.example/watch?v=dQw4w9WgXcQ",
        "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com/watch?v=short",
        "file:///etc/passwd",
        "",
    ],
)
def test_rejects_non_youtube(url):
    with pytest.raises(FetchError):
        video_id(url)
