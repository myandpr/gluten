#pragma once
#include <jni.h>
#include <Compression/CompressedReadBuffer.h>
#include <Formats/NativeReader.h>
#include <IO/ReadBuffer.h>
#include <Common/BlockIterator.h>


namespace local_engine
{
class ReadBufferFromJavaInputStream;
class ShuffleReader : BlockIterator
{
public:
    explicit ShuffleReader(std::unique_ptr<DB::ReadBuffer> in_, bool compressed);
    DB::Block * read();
    ~ShuffleReader();
    static jclass input_stream_class;
    static jmethodID input_stream_read;
    std::unique_ptr<DB::ReadBuffer> in;

private:
    std::unique_ptr<DB::CompressedReadBuffer> compressed_in;
    std::unique_ptr<DB::NativeReader> input_stream;
    DB::Block header;
};


class ReadBufferFromJavaInputStream : public DB::BufferWithOwnMemory<DB::ReadBuffer>
{
public:
    explicit ReadBufferFromJavaInputStream(jobject input_stream, size_t customize_buffer_size);
    ~ReadBufferFromJavaInputStream() override;

private:
    jobject java_in;
    size_t buffer_size;
    int readFromJava();
    bool nextImpl() override;
};

}
