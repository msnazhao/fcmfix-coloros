#pragma once

#include <link.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string_view>
#include <vector>

/**
 * Symbols of a library already loaded in this process, read from its file: .dynsym, .symtab
 * and the .symtab inside .gnu_debugdata. ART's internal functions are only in the last one on
 * release builds, which is why dlsym() is not enough for the hook engine.
 */
class ElfSymbols {
public:
    /** The first loaded object whose path ends with path_suffix, e.g. "/libart.so". */
    static std::unique_ptr<ElfSymbols> Open(std::string_view path_suffix);

    ~ElfSymbols();
    ElfSymbols(const ElfSymbols &) = delete;
    ElfSymbols &operator=(const ElfSymbols &) = delete;

    /** Address in this process, or null. */
    void *Find(std::string_view name) const;

    /** The first symbol starting with prefix, in table order, or null. */
    void *FindPrefix(std::string_view prefix) const;

    size_t symbol_count() const;

private:
    struct Table {
        const ElfW(Sym) *symbols;
        size_t count;
        const char *strings;
        size_t strings_size;
    };

    ElfSymbols() = default;

    /** Appends the symbol tables of one ELF image; reports .gnu_debugdata when asked. */
    bool AddTables(const uint8_t *data, size_t size, bool with_dynsym, const uint8_t **debugdata,
                   size_t *debugdata_size);

    template <typename Predicate>
    void *Scan(Predicate &&matches) const;

    uintptr_t bias_ = 0;
    void *file_ = nullptr;
    size_t file_size_ = 0;
    std::vector<uint8_t> debugdata_;
    std::vector<Table> tables_;
};
