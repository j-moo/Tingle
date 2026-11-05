#include "registration.h"
#include <string.h>

static char *space(char *p)
{
	while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') { ++p; }
	return p;
}

static int hex4(char **p)
{
	unsigned n = 0;
	for (int i = 0; i < 4; ++i) {
		unsigned char c = (unsigned char)**p;
		int digit = c >= '0' && c <= '9' ? c - '0' :
			c >= 'a' && c <= 'f' ? c - 'a' + 10 :
			c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
		if (digit < 0) { return -1; }
		n = n * 16 + (unsigned)digit;
		++*p;
	}
	return (int)n;
}

static bool string(char **cursor, char **value)
{
	char *p = *cursor;
	if (*p++ != '"') { return false; }
	char *out = p;
	*value = out;
	while (*p && *p != '"') {
		unsigned char c = (unsigned char)*p++;
		if (c < 32) { return false; }
		if (c == '\\') {
			c = (unsigned char)*p++;
			if (c == 'u') {
				int code = hex4(&p);
				if (code <= 0) { return false; } /* C strings cannot contain U+0000. */
				if (code >= 0xd800 && code <= 0xdbff) {
					if (*p++ != '\\' || *p++ != 'u') { return false; }
					int low = hex4(&p);
					if (low < 0xdc00 || low > 0xdfff) { return false; }
					code = 0x10000 + ((code - 0xd800) << 10) + low - 0xdc00;
				} else if (code >= 0xdc00 && code <= 0xdfff) { return false; }
				if (code < 0x80) { *out++ = (char)code; }
				else {
					if (code >= 0x10000) { *out++ = (char)(0xf0 | (code >> 18)); }
					if (code >= 0x800) {
						*out++ = (char)((code >= 0x10000 ? 0x80 : 0xe0) | ((code >> 12) & 63));
					}
					*out++ = (char)((code >= 0x800 ? 0x80 : 0xc0) | ((code >> 6) & 63));
					*out++ = (char)(0x80 | (code & 63));
				}
				continue;
			}
			switch (c) {
			case '"': case '\\': case '/': break;
			case 'b': c = '\b'; break;
			case 'f': c = '\f'; break;
			case 'n': c = '\n'; break;
			case 'r': c = '\r'; break;
			case 't': c = '\t'; break;
			default: return false;
			}
		} else if (c >= 0x80) {
			unsigned count;
			unsigned code;
			unsigned minimum;
			if (c >= 0xc2 && c <= 0xdf) { count = 1; code = c & 31; minimum = 0x80; }
			else if (c >= 0xe0 && c <= 0xef) { count = 2; code = c & 15; minimum = 0x800; }
			else if (c >= 0xf0 && c <= 0xf4) { count = 3; code = c & 7; minimum = 0x10000; }
			else { return false; }
			*out++ = (char)c;
			for (unsigned i = 0; i < count; ++i) {
				unsigned char next = (unsigned char)*p++;
				if ((next & 0xc0) != 0x80) { return false; }
				code = (code << 6) | (next & 63);
				*out++ = (char)next;
			}
			if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) { return false; }
			continue;
		}
		*out++ = (char)c;
	}
	if (*p != '"') { return false; }
	*cursor = p + 1;
	*out = 0;
	return true;
}

bool kr_json_parse(char *text, struct kr_json *out)
{
	memset(out, 0, sizeof(*out));
	char *p = space(text);
	if (*p++ != '{') { return false; }
	p = space(p);
	if (*p == '}') { return !*space(p + 1); }
	for (;;) {
		if (out->count == sizeof(out->fields) / sizeof(out->fields[0])) { return false; }
		struct kr_field *field = &out->fields[out->count];
		if (!string(&p, &field->key)) { return false; }
		for (size_t i = 0; i < out->count; ++i) {
			if (!strcmp(out->fields[i].key, field->key)) { return false; }
		}
		p = space(p);
		if (*p++ != ':') { return false; }
		p = space(p);
		field->string = *p == '"';
		char delimiter;
		if (field->string) {
			if (!string(&p, &field->value)) { return false; }
			p = space(p);
			delimiter = *p;
			if (delimiter) { ++p; }
		} else {
			/* Only unsigned integral scalars are used by these request messages.
			 * Explicitly reject booleans, null, fractions, exponents and nesting. */
			field->value = p;
			if (*p < '0' || *p > '9') { return false; }
			while (*p >= '0' && *p <= '9') { ++p; }
			if (p - field->value > 1 && field->value[0] == '0') { return false; }
			char *end = p;
			p = space(p);
			delimiter = *p;
			if (delimiter) { ++p; }
			*end = 0;
		}
		++out->count;
		if (delimiter == '}') { return !*space(p); }
		if (delimiter != ',') { return false; }
		p = space(p);
	}
}

static const char *get(const struct kr_json *obj, const char *key, bool string_value)
{
	for (size_t i = 0; i < obj->count; ++i) {
		if (!strcmp(obj->fields[i].key, key)) {
			return obj->fields[i].string == string_value ? obj->fields[i].value : NULL;
		}
	}
	return NULL;
}

const char *kr_json_string(const struct kr_json *obj, const char *key) { return get(obj, key, true); }
const char *kr_json_number(const struct kr_json *obj, const char *key) { return get(obj, key, false); }

size_t kr_json_quote(const char *text, char *out, size_t capacity)
{
	static const char hex[] = "0123456789abcdef";
	size_t n = 0;
	if (capacity < 3) { return 0; }
	out[n++] = '"';
	for (; *text; ++text) {
		unsigned char c = (unsigned char)*text;
		if (n + 8 > capacity) { return 0; }
		if (c < 32) {
			out[n++] = '\\'; out[n++] = 'u'; out[n++] = '0'; out[n++] = '0';
			out[n++] = hex[c >> 4]; out[n++] = hex[c & 15];
		} else {
			if (c == '\\' || c == '"') { out[n++] = '\\'; }
			out[n++] = (char)c;
		}
	}
	out[n++] = '"'; out[n] = 0;
	return n;
}
