# Work with pandoc Markdown sources, producing HTML & PDF outputs.
#
# The build is fast, we clean everytime to avoid managing dependencies.

src_dir := src
dest_dir := target
file := main
reader := xdg-open

out_release := ${dest_dir}/${name}-${version}
out := ${out_release}-pre
tmp := ${dest_dir}/${file}

pandoc_version=$(shell pandoc --version | head -n1 | cut -f2 -d' ')

all: pdf html

versions:
	pandoc --version
	typst --version
	structurizr version
	mmdc --version
	svgo --version
	typos --version

# Build an intermediary Markdown file, including rendered diagrams (C4+Mermaid).
markdown: clean
	if [ -f ${src_dir}/workspace.dsl ]; then \
        structurizr export --workspace ${src_dir}/workspace.dsl --output ${src_dir} --format mermaid >/dev/null; \
    fi
	find ${src_dir} -name "*.mmd" -exec mmdc --input {} --output {}.png >/dev/null \;
	cp -r ${src_dir} ${dest_dir}
	# SVG output does not work well, fallback to png
	mmdc --quiet --outputFormat png --input ${tmp}.md --output ${tmp}-mm.md >/dev/null

# Build the typst source file
typst: markdown
	pandoc --standalone --from markdown ${tmp}-mm.md --to typst \
	       --metadata version="$(version)" \
           --metadata pandoc_version="$(pandoc_version)" \
           --template=../rudder.typ.template --output ${tmp}.typ

# Build the HTML output
html: markdown
	pandoc --standalone --from markdown ${tmp}-mm.md --to html5 \
           --metadata date="`date -u --rfc-3339=date`" \
           --citeproc \
           --output ${out}.html

# Build the PDF
pdf: typst
	typst compile --root ${dest_dir} ${tmp}.typ ${out}.pdf

# Build a PDF release, including version in file name and
# additional compression.
pdf-release: typst optimize-images pdf
	qpdf ${out}.pdf --recompress-flate --compression-level=9 --object-streams=generate ${out_release}.pdf

# Build an HTML release, including version in file name and
# additional compression.
html-release: html optimize-images
	mv ${out}.html ${out_release}.html

release: pdf-release html-release

# Open the PDF in the local default reader
open-pdf: pdf
	${reader} ${out}.pdf

# Open the HTML in the local default browser
open-html: html
	${reader} ${out}.html

# Check for typos in the source
lint:
	typos ${src_dir}

# Fix the typos automatically
typos-fix:
	typos --write-changes ${src_dir}

# Format the markdown source in-place
#fmt:
#   # mdformat & pandoc both break the front matter is different ways.
#	mdformat ${src_dir}/${file}.md

# Optimize images in-place. Work on dest dir to also optimize generated diagrams.
optimize-images:
	find ${dest_dir} -name "*.svg" -exec svgo --multipass {} >/dev/null \;
	find ${dest_dir} -name "*.png" -exec zopflipng -y {} {} >/dev/null \;

# Clean all produced files
clean:
	rm -rf ${dest_dir} ${src_dir}/*.png ${src_dir}/*.mmd

.DEFAULT_GOAL=open-pdf