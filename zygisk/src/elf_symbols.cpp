#include "elf_symbols.h"

#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <xz.h>

#include <cstring>
#include <string>

namespace {

#if defined(__LP64__)
constexpr unsigned char kElfClass = ELFCLASS64;
#else
constexpr unsigned char kElfClass = ELFCLASS32;
#endif

/** Mini debug info is a few megabytes; anything near this is not a symbol table. */
constexpr size_t kMaxDebugdataSize = 256u << 20;

struct LoadedObject {
    std::string_view suffix;
    std::string path;
    uintptr_t bias = 0;
};

int FindLoadedObject(dl_phdr_info *info, size_t, void *data) {
    auto *wanted = static_cast<LoadedObject *>(data);
    if (info->dlpi_name == nullptr) return 0;
    std::string_view name(info->dlpi_name);
    if (!name.ends_with(wanted->suffix)) return 0;
    wanted->path = name;
    wanted->bias = info->dlpi_addr;
    return 1;
}

bool InRange(uint64_t offset, uint64_t length, size_t size) {
    return offset <= size && length <= size - offset;
}

bool Decompress(const uint8_t *in, size_t in_size, std::vector<uint8_t> &out) {
    xz_crc32_init();
    xz_crc64_init();
    xz_dec *decoder = xz_dec_init(XZ_DYNALLOC, 1u << 26);
    if (decoder == nullptr) return false;

    out.resize(in_size * 4 + (64u << 10));
    xz_buf buffer{};
    buffer.in = in;
    buffer.in_size = in_size;
    bool ok = false;
    while (true) {
        buffer.out = out.data();
        buffer.out_size = out.size();
        xz_ret result = xz_dec_run(decoder, &buffer);
        if (result == XZ_STREAM_END) {
            out.resize(buffer.out_pos);
            ok = true;
            break;
        }
        // XZ_OK with room left in the output means the input ran out early.
        if (result != XZ_OK || buffer.out_pos < buffer.out_size ||
            out.size() >= kMaxDebugdataSize) {
            break;
        }
        out.resize(out.size() * 2);
    }
    xz_dec_end(decoder);
    return ok;
}

}  // namespace

std::unique_ptr<ElfSymbols> ElfSymbols::Open(std::string_view path_suffix) {
    LoadedObject object;
    object.suffix = path_suffix;
    if (dl_iterate_phdr(FindLoadedObject, &object) == 0) return nullptr;

    int fd = open(object.path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return nullptr;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return nullptr;
    }
    void *file = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (file == MAP_FAILED) return nullptr;

    std::unique_ptr<ElfSymbols> symbols(new ElfSymbols());
    symbols->bias_ = object.bias;
    symbols->file_ = file;
    symbols->file_size_ = static_cast<size_t>(st.st_size);

    const uint8_t *debugdata = nullptr;
    size_t debugdata_size = 0;
    if (!symbols->AddTables(static_cast<const uint8_t *>(file), symbols->file_size_, true,
                            &debugdata, &debugdata_size)) {
        return nullptr;
    }
    // A library without mini debug info still has .dynsym, so a failure here is not fatal:
    // the hook engine reports which symbol it could not find.
    if (debugdata != nullptr && Decompress(debugdata, debugdata_size, symbols->debugdata_)) {
        symbols->AddTables(symbols->debugdata_.data(), symbols->debugdata_.size(), false, nullptr,
                           nullptr);
    }
    return symbols;
}

ElfSymbols::~ElfSymbols() {
    if (file_ != nullptr) munmap(file_, file_size_);
}

bool ElfSymbols::AddTables(const uint8_t *data, size_t size, bool with_dynsym,
                           const uint8_t **debugdata, size_t *debugdata_size) {
    if (size < sizeof(ElfW(Ehdr))) return false;
    const auto *header = reinterpret_cast<const ElfW(Ehdr) *>(data);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 || header->e_ident[EI_CLASS] != kElfClass ||
        header->e_shentsize != sizeof(ElfW(Shdr)) ||
        !InRange(header->e_shoff, uint64_t{header->e_shnum} * sizeof(ElfW(Shdr)), size)) {
        return false;
    }
    const auto *sections = reinterpret_cast<const ElfW(Shdr) *>(data + header->e_shoff);
    const size_t section_count = header->e_shnum;

    const char *section_names = nullptr;
    size_t section_names_size = 0;
    if (header->e_shstrndx < section_count) {
        const auto &names = sections[header->e_shstrndx];
        if (names.sh_type != SHT_NOBITS && InRange(names.sh_offset, names.sh_size, size)) {
            section_names = reinterpret_cast<const char *>(data + names.sh_offset);
            section_names_size = names.sh_size;
        }
    }

    for (size_t i = 0; i < section_count; ++i) {
        const auto &section = sections[i];
        if (section.sh_type == SHT_NOBITS || !InRange(section.sh_offset, section.sh_size, size)) {
            continue;
        }
        if (section.sh_type == SHT_SYMTAB || (with_dynsym && section.sh_type == SHT_DYNSYM)) {
            if (section.sh_link >= section_count) continue;
            const auto &strings = sections[section.sh_link];
            if (strings.sh_type == SHT_NOBITS ||
                !InRange(strings.sh_offset, strings.sh_size, size)) {
                continue;
            }
            tables_.push_back(Table{
                .symbols = reinterpret_cast<const ElfW(Sym) *>(data + section.sh_offset),
                .count = static_cast<size_t>(section.sh_size / sizeof(ElfW(Sym))),
                .strings = reinterpret_cast<const char *>(data + strings.sh_offset),
                .strings_size = static_cast<size_t>(strings.sh_size),
            });
        } else if (debugdata != nullptr && section_names != nullptr &&
                   section.sh_name < section_names_size) {
            const char *name = section_names + section.sh_name;
            std::string_view view(name, strnlen(name, section_names_size - section.sh_name));
            if (view == ".gnu_debugdata") {
                *debugdata = data + section.sh_offset;
                *debugdata_size = static_cast<size_t>(section.sh_size);
            }
        }
    }
    return true;
}

template <typename Predicate>
void *ElfSymbols::Scan(Predicate &&matches) const {
    for (const auto &table : tables_) {
        for (size_t i = 0; i < table.count; ++i) {
            const auto &symbol = table.symbols[i];
            if (symbol.st_shndx == SHN_UNDEF || symbol.st_value == 0 ||
                symbol.st_name >= table.strings_size) {
                continue;
            }
            const unsigned type = symbol.st_info & 0xf;
            if (type != STT_FUNC && type != STT_OBJECT) continue;
            const char *name = table.strings + symbol.st_name;
            std::string_view view(name, strnlen(name, table.strings_size - symbol.st_name));
            if (matches(view)) return reinterpret_cast<void *>(bias_ + symbol.st_value);
        }
    }
    return nullptr;
}

void *ElfSymbols::Find(std::string_view name) const {
    return Scan([name](std::string_view candidate) { return candidate == name; });
}

void *ElfSymbols::FindPrefix(std::string_view prefix) const {
    return Scan([prefix](std::string_view candidate) { return candidate.starts_with(prefix); });
}

size_t ElfSymbols::symbol_count() const {
    size_t count = 0;
    for (const auto &table : tables_) count += table.count;
    return count;
}
