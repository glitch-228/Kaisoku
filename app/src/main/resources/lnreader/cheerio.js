/* Jsoup-backed subset of Cheerio used by hosted LNReader plugins. */
globalThis.cheerio = {
    load: function (html, options, isDocument) {
        function native(op, args) {
            var result = JSON.parse(__nativeDom(op, JSON.stringify(args)));
            if (result.error) throw new Error(result.error);
            return result.value;
        }
        var roots = native('parse', [String(html), '']);
        function selection(ids, previous) {
            var result = Object.create(methods);
            result._ids = ids;
            result.prevObject = previous;
            result.length = ids.length;
            ids.forEach(function (id, i) { result[i] = {_id: id}; });
            return result;
        }
        function nodes(value) {
            if (value == null) return [];
            if (value._ids) return value._ids;
            if (value._id !== undefined) return [value._id];
            if (Array.isArray(value)) return value.reduce(function (ids, v) { return ids.concat(nodes(v)); }, []);
            throw new Error('Expected a DOM node or selection');
        }
        var methods = {
            find: function (selector) { return selection(native('find', [this._ids, selector]), this); },
            text: function (value) {
                if (value === undefined) return native('text', [this._ids]);
                native('setText', [this._ids, String(value)]); return this;
            },
            html: function (value) {
                if (value === undefined) return native('html', [this._ids]);
                native('setHtml', [this._ids, String(value)]); return this;
            },
            attr: function (name, value) {
                if (value === undefined) { var v = native('attr', [this._ids, name]); return v == null ? undefined : v; }
                native('setAttr', [this._ids, name, String(value)]); return this;
            },
            prop: function (name) {
                if (name === 'outerHTML') return native('outerHtml', [this._ids.slice(0, 1)]);
                if (name === 'innerHTML') return this.html();
                if (name === 'textContent' || name === 'innerText') return this.text();
                return this.attr(name);
            },
            data: function (name) { return this.attr('data-' + name.replace(/[A-Z]/g, function (c) { return '-' + c.toLowerCase(); })); },
            each: function (callback) {
                for (var i = 0; i < this.length; i++) if (callback.call(this[i], i, this[i]) === false) break;
                return this;
            },
            map: function (callback) {
                var values = [];
                this.each(function (i, node) {
                    var value = callback.call(node, i, node);
                    if (value != null) values = values.concat(value);
                });
                return {get: function (i) { return i === undefined ? values : values[i < 0 ? values.length + i : i]; }, toArray: function () { return values; }};
            },
            get: function (i) { return i === undefined ? this.toArray() : this[i < 0 ? this.length + i : i]; },
            toArray: function () { return this._ids.map(function (id) { return {_id: id}; }); },
            eq: function (i) { var node = this.get(i); return selection(node ? [node._id] : [], this); },
            first: function () { return this.eq(0); },
            last: function () { return this.eq(-1); },
            slice: function (start, end) { return selection(this._ids.slice(start, end), this); },
            end: function () { return this.prevObject || selection([]); },
            filter: function (selector) {
                if (typeof selector !== 'function') return selection(native('filter', [this._ids, selector]), this);
                var ids = [];
                this.each(function (i, node) { if (selector.call(node, i, node)) ids.push(node._id); });
                return selection(ids, this);
            },
            not: function (selector) {
                var excluded = this.filter(selector)._ids;
                return selection(this._ids.filter(function (id) { return excluded.indexOf(id) < 0; }), this);
            },
            is: function (selector) { return this.filter(selector).length > 0; },
            hasClass: function (name) { return (' ' + (this.attr('class') || '') + ' ').indexOf(' ' + name + ' ') >= 0; },
            toString: function () { return native('outerHtml', [this._ids]); }
        };
        ['parent', 'next', 'prev', 'children', 'contents'].forEach(function (op) {
            methods[op] = function (selector) {
                var result = selection(native(op, [this._ids]), this);
                return selector ? selection(result.filter(selector)._ids, this) : result;
            };
        });
        ['remove', 'replaceWith', 'append', 'prepend', 'removeAttr'].forEach(function (op) {
            methods[op] = function (value) { native(op, [this._ids, value == null ? '' : String(value)]); return this; };
        });
        function $(selector, context) {
            if (typeof selector !== 'string') return selection(nodes(selector));
            return selection(context ? nodes(context) : roots).find(selector);
        }
        $.root = function () { return selection(roots); };
        $.html = function (value) { return value === undefined ? $.root().html() : $(value).toString(); };
        $.text = function (value) { return value === undefined ? $.root().text() : $(value).text(); };
        return $;
    }
};
